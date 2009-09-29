;   Copyright (c) Zachary Tellman. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns examples.n-body
  (:use [penumbra slate compute])
  (:use [penumbra.opengl.texture])
  (:use [clojure.contrib.seq-utils :only (partition-all flatten)]))

;NOT COMPLETE

(defn gen [min max]
  (+ min (* (rand) (- max min))))

(defn gen-mass [num min max]
  (wrap (map float (concat [5e12] (take num (repeatedly #(gen min max))))) 1))

(defn gen-velocity [num min max]
  (wrap (map float (concat [0.0 0.0 0.0] (take (* 3 num) (repeatedly #(gen min max))))) 3))

(defn gen-position [num min max]
  (wrap (map float (concat [0.0 0.0 0.0] (take (* 3 num) (repeatedly #(gen min max))))) 3))

(def slate (create-slate))

(with-slate slate
  (defmap add
    (+ %1 (* k %2)))

  (defmap kinetic-energy
    (let [m %1, v %2]
      (float3 (* 0.5 m (dot v v)))))

  (defmap potential-energy
    (let [m1 %1, m2 (lookup %1 idx)
          p1 %2, p2 (lookup %2 idx)]
      (float3 (* g m1 m2 (length (- p1 p2))))))

  (defmap gravity
    (let [m2 (lookup %1 idx)
          p1 %2, p2 (lookup %2 idx)
          diff (- p2 p1)]
      (? (= :index (float idx))
        (float3 0.0)
        (* (normalize diff) (/ (* g m2) (dot diff diff))))))

  (defreduce sum (+ %1 %2)))

(defn prn-tex [t]
  (acquire! t)
  (println (partition 3 (unwrap* t))))

(defn chunked-add [f chunk-size coll-size]
  (let [s (partition-all chunk-size (range coll-size))
        add #(add {:k 1.0} [%1 %2])]
    (reduce add (map #(reduce add (map f %)) s))))

(defn run-sim [num iterations]
  (let [m (gen-mass num 1e3 1e4)
        v (gen-velocity num -1 1)
        p (gen-position num -100 100)
        dt 1.0]
    (println "generated")
    (loop [v v, p p, i 1]
      (if (> i iterations)
        nil
        (let [a  (chunked-add #(gravity {:g 6.673e-11 :idx %} [ [m] [p] ]) 10 num)
              v* (add {:k dt} [ v a ])
              p* (add {:k dt} [ p [v*] ])
              ke (first (sum (kinetic-energy [ [m] [v*] ])))
              pe (first (sum (chunked-add #(potential-energy {:idx % :g 6.673e-11 } [ [m] [p*] ]) 10 num)))]
          (println "kinetic:" ke "potential:" pe "total" (+ ke pe))
          (recur v* p* (inc i)))))))

(with-slate slate
  (run-sim 100 10))