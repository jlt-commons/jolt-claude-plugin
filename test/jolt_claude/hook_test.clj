(ns jolt-claude.hook-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt-claude.hook :as hook]))

(deftest only-jolt-source-files-are-checked
  (is (hook/checked-file? "/p/src/a/core.clj"))
  (is (hook/checked-file? "/p/src/a/core.cljc"))
  (is (hook/checked-file? "/p/deps.edn"))
  (is (hook/checked-file? "/p/src/a/core.jolt"))
  (is (not (hook/checked-file? "/p/src/a/core.cljs")))
  (is (not (hook/checked-file? "/p/README.md")))
  (is (not (hook/checked-file? nil))))

(deftest the-edited-path-comes-from-the-tool-input
  (is (= "/p/a.clj" (hook/edited-file {:tool_input {:file_path "/p/a.clj"}})))
  (is (= "/p/a.clj" (hook/edited-file {:tool_input {:path "/p/a.clj"}})))
  (is (= "/p/a.clj" (hook/edited-file {:tool_response {:filePath "/p/a.clj"}}))))

(deftest root-is-the-nearest-project-dir
  (let [root (str (fs/canonicalize (fs/create-temp-dir)))]
    (fs/create-dirs (str root "/src/a"))
    (spit (str root "/deps.edn") "{}")
    (is (= root (hook/project-root (str root "/src/a/core.clj"))))
    (testing "no project file: the file's own dir"
      (let [loose (str (fs/canonicalize (fs/create-temp-dir)))]
        (is (= loose (hook/project-root (str loose "/x.clj"))))))))

(def ^:private err {:row 2 :col 14 :severity "error" :code "unresolved-symbol"
                    :message "Unresolved symbol: nope"})
(def ^:private warn {:row 5 :col 1 :severity "warning" :code "unused-binding"
                     :message "unused binding x"})

(deftest decide-what-to-tell-the-agent
  (testing "clean file: nothing"
    (is (nil? (hook/decide "src/a.clj" {:ok true :status "ok"} []))))
  (testing "unrepairable syntax blocks with the position"
    (let [out (hook/decide "src/a.clj" {:ok false :status "unbalanced"
                                        :note "line 3, col 10: `)` does not close the form"} nil)]
      (is (= "block" (:decision out)))
      (is (str/includes? (:reason out) "line 3, col 10"))))
  (testing "an error-level finding blocks and lists it"
    (let [out (hook/decide "src/a.clj" {:ok true :status "ok"} [err warn])]
      (is (= "block" (:decision out)))
      (is (str/includes? (:reason out) "src/a.clj:2:14: error: Unresolved symbol: nope"))
      (is (str/includes? (:reason out) "unused binding x"))))
  (testing "warnings only: context, not a block"
    (let [out (hook/decide "src/a.clj" {:ok true :status "ok"} [warn])]
      (is (nil? (:decision out)))
      (is (str/includes? (get-in out [:hookSpecificOutput :additionalContext]) "src/a.clj:5:1: warning"))
      (is (= "PostToolUse" (get-in out [:hookSpecificOutput :hookEventName])))))
  (testing "a repair is reported so the agent can check the placement"
    (let [out (hook/decide "src/a.clj" {:ok true :status "repaired" :written true
                                        :note "auto-closed 2 unclosed delimiter(s): appended `))`"} [])]
      (is (str/includes? (get-in out [:hookSpecificOutput :additionalContext]) "appended `))`"))))
  (testing "a long list is capped"
    (let [out (hook/decide "src/a.clj" {:ok true :status "ok"} (repeat 30 warn))]
      (is (str/includes? (get-in out [:hookSpecificOutput :additionalContext]) "and 20 more")))))

(defn- lsp-available? []
  (some? (fs/which (or (System/getenv "JOLT_LSP") "jolt-lsp"))))

(deftest end-to-end-against-jolt-lsp
  (if-not (lsp-available?)
    (println "skipping end-to-end: jolt-lsp not on PATH")
    (let [root (str (fs/canonicalize (fs/create-temp-dir)))
          f (str root "/src/a/core.clj")]
      (fs/create-dirs (str root "/src/a"))
      (spit (str root "/deps.edn") "{:paths [\"src\"]}")
      (testing "a dropped closer is repaired on disk"
        (spit f "(ns a.core)\n(defn f [x]\n  (inc x)\n")
        (let [out (hook/run {:hook_event_name "PostToolUse" :tool_name "Write"
                             :tool_input {:file_path f}})]
          (is (nil? (:decision out)))
          (is (str/includes? (slurp f) "(inc x)"))
          (is (= (count (re-seq #"\(" (slurp f))) (count (re-seq #"\)" (slurp f)))))))
      (testing "an unresolved symbol blocks"
        (spit f "(ns a.core)\n(defn f [x] (nope x))\n")
        (let [out (hook/run {:tool_input {:file_path f}})]
          (is (= "block" (:decision out)))
          (is (str/includes? (:reason out) "nope"))))
      (testing "a non-Clojure file is ignored"
        (is (nil? (hook/run {:tool_input {:file_path (str root "/README.md")}})))))))
