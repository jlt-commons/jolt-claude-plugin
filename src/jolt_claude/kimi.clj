(ns jolt-claude.kimi
  "The same checks as jolt-claude.hook, for Kimi Code's hooks.

  Kimi runs PostToolUse hooks fire-and-forget: their output is dropped, so
  they cannot block or tell the agent anything. Only PreToolUse and Stop
  hooks can block, and a blocked Stop hands its reason to the agent, which
  carries on. So the work is split:

  - PreToolUse (Write, Edit): refuse the call when the text it would leave
    does not read as Clojure, with the position.
  - PostToolUse (Write, Edit): repair a dropped closer on disk
    (`jolt-lsp check --fix`) and remember the file for this session.
  - Stop: lint the files touched since the last Stop and block on
    error-level findings, so the agent fixes them before it finishes.

  Hook input JSON on stdin, output JSON on stdout. KIMI_HOOKS_STATE_DIR
  overrides where the per-session file lists live."
  (:require [babashka.fs :as fs]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [jolt-claude.hook :as hook]))

(defn- deny [reason]
  {:hookSpecificOutput {:permissionDecision "deny"
                        :permissionDecisionReason reason}})

(defn- occurrences [text s]
  (loop [i 0 n 0]
    (let [j (str/index-of text s i)]
      (if (or (nil? j) (empty? s)) n (recur (+ j (count s)) (inc n))))))

(defn proposed-text
  "The text the file would hold after this Write or Edit, given its
  current text; nil for other tools and for an edit the tool will refuse
  itself (old_string missing, or ambiguous without replace_all)."
  [{:keys [tool_name tool_input]} current]
  (let [{:keys [content old_string new_string replace_all]} tool_input]
    (case tool_name
      "Write" content
      "Edit" (when (and current (string? old_string) (string? new_string))
               (let [n (occurrences current old_string)]
                 (cond
                   (zero? n) nil
                   replace_all (str/replace current old_string new_string)
                   (= 1 n) (str/replace-first current old_string new_string)
                   :else nil)))
      nil)))

(defn pre-decision
  "Deny when jolt-lsp's check of the proposed text failed; a text it can
  repair goes through and PostToolUse repairs it on disk."
  [rel check]
  (when (and check (not (:ok check)))
    (deny (str "This change would leave " rel " unreadable as Clojure: " (:note check)
               "\nFix the text and resend it."))))

(defn stop-decision
  "Block the stop when any touched file has an error-level finding.
  `findings` is {rel-path [finding ...]}."
  [findings]
  (let [bad (into (sorted-map)
                  (filter (fn [[_ fs]] (some #(= "error" (:severity %)) fs)))
                  findings)]
    (when (seq bad)
      (deny (str/join "\n" (cons "jolt-lsp found errors in files changed this turn; fix them before finishing:"
                                 (mapcat (fn [[rel fs]] (hook/finding-lines rel fs)) bad)))))))

;; --- per-session state ------------------------------------------------------

(defn- state-file [dir session]
  (str (fs/path dir (str (str/replace (str session) #"[^A-Za-z0-9._-]" "_") ".edn"))))

(defn- read-touched [f]
  (or (try (edn/read-string (slurp f)) (catch Throwable _ nil)) #{}))

(defn remember!
  "Add `path` to the files touched in `session`."
  [dir session path]
  (fs/create-dirs dir)
  (let [f (state-file dir session)]
    (spit f (pr-str (conj (read-touched f) path)))))

(defn take-touched!
  "The files touched in `session`, clearing the list."
  [dir session]
  (let [f (state-file dir session)
        touched (read-touched f)]
    (fs/delete-if-exists f)
    touched))

(defn default-state-dir []
  (or (System/getenv "KIMI_HOOKS_STATE_DIR")
      (str (fs/path (System/getProperty "java.io.tmpdir") "jolt-kimi-hooks"))))

;; --- the events -------------------------------------------------------------

(defn- rel-of [path]
  (let [root (hook/project-root path)]
    [root (str (fs/relativize root path))]))

(defn- pre-tool-use [bin input]
  (let [path (hook/edited-file input)]
    (when (hook/checked-file? path)
      (let [path (str (fs/absolutize path))
            current (when (fs/exists? path) (slurp path))]
        (when-let [text (proposed-text input current)]
          (let [[root rel] (rel-of path)
                tmp (str (fs/create-temp-file {:suffix (str "." (fs/extension path))}))]
            (try
              (spit tmp text)
              (pre-decision rel (hook/lsp bin root "check" tmp))
              (finally (fs/delete-if-exists tmp)))))))))

(defn- post-tool-use [bin state input]
  (let [path (hook/edited-file input)]
    (when (and (hook/checked-file? path) (fs/exists? path))
      (let [path (str (fs/canonicalize path))
            [root _] (rel-of path)]
        (hook/lsp bin root "check" path "--fix")
        (remember! state (:session_id input) path))))
  nil)

(defn- stop [bin state input]
  (stop-decision
   (into {}
         (for [path (take-touched! state (:session_id input))
               :when (fs/exists? path)
               :let [[root rel] (rel-of path)]]
           [rel (hook/lsp bin root "diagnostics" path)]))))

(defn run
  "The hook output for one Kimi hook input, or nil."
  [state input]
  (when-let [bin (hook/lsp-bin)]
    (case (:hook_event_name input)
      "PreToolUse" (pre-tool-use bin input)
      "PostToolUse" (post-tool-use bin state input)
      "Stop" (stop bin state input)
      nil)))

(defn -main [& _]
  (let [input (try (json/read-str (slurp *in*) :key-fn keyword)
                   (catch Throwable _ nil))
        out (when input
              (try (run (default-state-dir) input)
                   (catch Throwable e
                     (binding [*out* *err*]
                       (println "jolt-kimi hook:" (ex-message e)))
                     nil)))]
    (when out
      (println (json/write-str out)))
    (flush)
    (System/exit 0)))
