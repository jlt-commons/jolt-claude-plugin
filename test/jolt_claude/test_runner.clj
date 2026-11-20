(ns jolt-claude.test-runner
  (:require [clojure.test :as t]
            jolt-claude.hook-test))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'jolt-claude.hook-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
