(ns jolt-claude.test-runner
  (:require [clojure.test :as t]
            jolt-claude.hook-test
            jolt-claude.kimi-test))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests (quote jolt-claude.hook-test) (quote jolt-claude.kimi-test))]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
