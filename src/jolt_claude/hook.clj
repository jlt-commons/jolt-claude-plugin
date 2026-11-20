(ns jolt-claude.hook
  "Claude Code PostToolUse hook for Jolt/Clojure files.

  After a Write or Edit lands, the file is repaired on disk when its only
  problem is a dropped closer (`jolt-lsp check --fix`), then linted
  (`jolt-lsp diagnostics`). Unrepairable syntax or an error-level finding
  blocks, so the agent sees it and fixes the file; warnings and repairs are
  passed back as context.

  Reads the hook input JSON on stdin and writes the hook output JSON on
  stdout. JOLT_LSP names the jolt-lsp binary (default: jolt-lsp on PATH);
  without one the hook does nothing."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(def ^:private extensions #{"clj" "cljc" "edn" "jolt"})

(def ^:private project-files ["deps.edn" "bb.edn" "project.clj"])

(def ^:private max-findings 10)

(defn checked-file?
  "Whether `path` is a file jolt-lsp reads."
  [path]
  (boolean (and (string? path)
                (contains? extensions (fs/extension path)))))

(defn edited-file
  "The path the tool wrote, from the hook input."
  [{:keys [tool_input tool_response]}]
  (or (:file_path tool_input)
      (:path tool_input)
      (:filePath tool_response)))

(defn project-root
  "The nearest directory at or above `path` holding a project file, else
  the file's own directory."
  [path]
  (let [dir (str (fs/parent (fs/absolutize path)))]
    (or (loop [d dir]
          (cond
            (nil? d) nil
            (some #(fs/exists? (fs/path d %)) project-files) d
            :else (recur (some-> (fs/parent d) str))))
        dir)))

(defn- finding-line [rel {:keys [row col severity message]}]
  (str rel ":" row ":" col ": " severity ": " message))

(defn finding-lines
  "Findings as file:line:col lines, capped at max-findings."
  [rel findings]
  (let [shown (take max-findings findings)
        more (- (count findings) (count shown))]
    (cond-> (mapv #(finding-line rel %) shown)
      (pos? more) (conj (str "... and " more " more")))))

(defn- context [text]
  {:hookSpecificOutput {:hookEventName "PostToolUse"
                        :additionalContext text}})

(defn decide
  "The hook output for file `rel`, given jolt-lsp's check result and the
  file's diagnostics, or nil when there is nothing to say."
  [rel check findings]
  (let [repaired (when (= "repaired" (:status check))
                   (str "jolt-lsp repaired " rel ": " (:note check)))
        errors (filter #(= "error" (:severity %)) findings)]
    (cond
      (not (:ok check))
      {:decision "block"
       :reason (str rel " does not read as Clojure: " (:note check)
                    "\nFix the text (jolt-lsp's jolt_check gives the position).")}

      (seq errors)
      {:decision "block"
       :reason (str/join "\n" (concat (some-> repaired vector)
                                      [(str (count errors) " error(s) in " rel ":")]
                                      (finding-lines rel findings)))}

      (or repaired (seq findings))
      (context (str/join "\n" (concat (some-> repaired vector)
                                      (finding-lines rel findings)))))))

(defn lsp-bin
  "The jolt-lsp binary's path, or nil when there is none."
  []
  (let [b (or (System/getenv "JOLT_LSP") "jolt-lsp")]
    (some-> (fs/which b) str)))

(defn lsp
  "Run jolt-lsp with --json and `args` in `root`; the parsed output, or nil
  when it printed nothing readable."
  [bin root & args]
  (let [{:keys [out]} (apply p/shell {:out :string :err :string :continue true :dir root}
                             bin "--json" (concat args ["--root" root]))]
    (try (json/read-str out :key-fn keyword)
         (catch Throwable _ nil))))

(defn run
  "The hook output for one hook input map, or nil."
  [input]
  (let [path (edited-file input)
        bin (lsp-bin)]
    (when (and bin (checked-file? path) (fs/exists? path))
      (let [path (str (fs/canonicalize path))
            root (project-root path)
            rel (str (fs/relativize root path))
            check (lsp bin root "check" path "--fix")]
        (when check
          (decide rel check
                  (when (:ok check)
                    (lsp bin root "diagnostics" path))))))))

(defn -main [& _]
  (let [input (try (json/read-str (slurp *in*) :key-fn keyword)
                   (catch Throwable _ nil))
        out (when input
              (try (run input)
                   (catch Throwable e
                     (binding [*out* *err*]
                       (println "jolt-claude hook:" (ex-message e)))
                     nil)))]
    (when out
      (println (json/write-str out)))
    (flush)
    (System/exit 0)))
