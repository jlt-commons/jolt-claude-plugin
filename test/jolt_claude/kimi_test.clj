(ns jolt-claude.kimi-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt-claude.kimi :as kimi]))

(defn- tmp-dir []
  (str (fs/canonicalize (fs/create-temp-dir))))

(deftest the-text-a-write-or-edit-would-leave
  (testing "Write: the content"
    (is (= "(ns a)" (kimi/proposed-text {:tool_name "Write" :tool_input {:path "/x.clj" :content "(ns a)"}} nil))))
  (testing "Edit: one replacement in the current text"
    (is (= "(ns a) (def y 2)"
           (kimi/proposed-text {:tool_name "Edit" :tool_input {:old_string "x 1" :new_string "y 2"}}
                               "(ns a) (def x 1)"))))
  (testing "Edit with replace_all"
    (is (= "b b" (kimi/proposed-text {:tool_name "Edit" :tool_input {:old_string "a" :new_string "b" :replace_all true}}
                                     "a a"))))
  (testing "an edit the tool itself will refuse (missing or ambiguous old_string): nil"
    (is (nil? (kimi/proposed-text {:tool_name "Edit" :tool_input {:old_string "zz" :new_string "y"}} "a a")))
    (is (nil? (kimi/proposed-text {:tool_name "Edit" :tool_input {:old_string "a" :new_string "y"}} "a a"))))
  (testing "another tool: nil"
    (is (nil? (kimi/proposed-text {:tool_name "Read" :tool_input {:path "/x.clj"}} "a")))))

(deftest pre-decision-denies-only-unreadable-text
  (is (nil? (kimi/pre-decision "src/a.clj" {:ok true :status "ok"})))
  (is (nil? (kimi/pre-decision "src/a.clj" {:ok true :status "repaired" :note "appended `)`"})))
  (let [out (kimi/pre-decision "src/a.clj" {:ok false :note "line 3, col 10: `)` does not close"})]
    (is (= "deny" (get-in out [:hookSpecificOutput :permissionDecision])))
    (is (str/includes? (get-in out [:hookSpecificOutput :permissionDecisionReason]) "line 3, col 10"))))

(deftest stop-decision-blocks-on-errors-only
  (let [err {:row 2 :col 3 :severity "error" :message "Unresolved symbol: nope"}
        warn {:row 4 :col 1 :severity "warning" :message "unused binding x"}]
    (is (nil? (kimi/stop-decision {"src/a.clj" [warn]})))
    (is (nil? (kimi/stop-decision {})))
    (let [out (kimi/stop-decision {"src/a.clj" [err warn] "src/b.clj" []})
          reason (get-in out [:hookSpecificOutput :permissionDecisionReason])]
      (is (= "deny" (get-in out [:hookSpecificOutput :permissionDecision])))
      (is (str/includes? reason "src/a.clj:2:3: error: Unresolved symbol: nope"))
      (is (str/includes? reason "unused binding x"))
      (is (not (str/includes? reason "src/b.clj"))))))

(deftest touched-files-are-kept-per-session
  (let [dir (tmp-dir)]
    (kimi/remember! dir "s1" "/p/a.clj")
    (kimi/remember! dir "s1" "/p/b.clj")
    (kimi/remember! dir "s1" "/p/a.clj")
    (kimi/remember! dir "s2" "/p/c.clj")
    (is (= #{"/p/a.clj" "/p/b.clj"} (kimi/take-touched! dir "s1")))
    (testing "taking them clears the session"
      (is (= #{} (kimi/take-touched! dir "s1"))))
    (is (= #{"/p/c.clj"} (kimi/take-touched! dir "s2")))
    (testing "a session id cannot escape the state dir"
      (kimi/remember! dir "../../evil" "/p/x.clj")
      (is (empty? (filter #(str/includes? (str %) "evil") (fs/list-dir (fs/parent dir))))))))

(defn- lsp? [] (some? (fs/which (or (System/getenv "JOLT_LSP") "jolt-lsp"))))

(deftest end-to-end-against-jolt-lsp
  (if-not (lsp?)
    (println "skipping kimi end-to-end: jolt-lsp not on PATH")
    (let [root (tmp-dir)
          state (tmp-dir)
          f (str root "/src/a/core.clj")]
      (fs/create-dirs (str root "/src/a"))
      (spit (str root "/deps.edn") "{:paths [\"src\"]}")
      (spit f "(ns a.core)\n(defn f [x] (inc x))\n")
      (testing "PreToolUse refuses an edit that leaves the file unreadable"
        (let [out (kimi/run state {:hook_event_name "PreToolUse" :tool_name "Edit" :session_id "s"
                                   :tool_input {:path f :old_string "(inc x)" :new_string "(inc x]"}})]
          (is (= "deny" (get-in out [:hookSpecificOutput :permissionDecision])))))
      (testing "PreToolUse lets a repairable write through"
        (is (nil? (kimi/run state {:hook_event_name "PreToolUse" :tool_name "Write" :session_id "s"
                                   :tool_input {:path f :content "(ns a.core)\n(defn f [x] (nope x)\n"}}))))
      (testing "PostToolUse repairs the file on disk"
        (spit f "(ns a.core)\n(defn f [x] (nope x)\n")
        (kimi/run state {:hook_event_name "PostToolUse" :tool_name "Write" :session_id "s"
                         :tool_input {:path f}})
        (let [text (slurp f)]
          (is (str/includes? text "(nope x)"))
          (is (= (count (re-seq #"\(" text)) (count (re-seq #"\)" text))))))
      (testing "Stop reports the error in a file touched this session, once"
        (let [out (kimi/run state {:hook_event_name "Stop" :session_id "s"})]
          (is (str/includes? (get-in out [:hookSpecificOutput :permissionDecisionReason]) "nope")))
        (is (nil? (kimi/run state {:hook_event_name "Stop" :session_id "s"})))))))
