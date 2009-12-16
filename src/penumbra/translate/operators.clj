;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns penumbra.translate.operators
  (:use [penumbra.geometry])
  (:use [penumbra.translate core])
  (:use [clojure.contrib.seq-utils :only (indexed group-by)])
  (:use [clojure.contrib.def :only (defn-memo defvar-)])
  (:use [penumbra.translate.core])
  (:require [clojure.zip :as zip]))

;;

(def *dim-element* nil)
(def *typeof-dim* nil)
(def *typeof-element* nil)
(def *typeof-param* nil)

;;utilities

(defn first= [x y]
  (and (seq? x) (= (first x) y)))

(defn int? [p]
  (let [cls (class p)]
    (if (or (= cls Integer) (= cls Integer/TYPE)) true false)))

(defn tag= [x t]
  (and (meta? x) (= t (:tag ^x))))

(defn apply-transforms [funs tree]
  (reduce #(tree-map %2 %1) tree funs))

(defn replace-with [from to]
  #(if (= from %) to))

;;elements

(defn element? [s]
  (or
    (and (symbol? s) (re-find #"%$|%[0-9]+" (name s)))
    (and (seq? s) (< 1 (count s)) (element? (first s)))))

(defn-memo create-element [index]
  (symbol (str "%" (inc index))))

(defn element-index [x]
  (let [x (if (sequential? x) (first x) x)]
    (if (= '% x)
      0
      (dec (Integer/parseInt (.substring (name x) 1))))))

(defn- process-elements
  [coll]
  (vec
   (map
    #(if (vector? %)
       (add-meta (first %) :persist true)
       %)
    coll)))

(defn apply-element-transform
  [f x]
  (cond
   (element? x)    (let [x* (or (f x) x)] (with-meta x* (merge (meta x) (meta x*))))
   (sequential? x) (mimic-expr x (map #(apply-element-transform f %) x))
   :else           x))

;;results

(defn- result?
  "This assumes you only traverse down the last element of the tree"
  [x]
  (or
    (vector? x)
    (not (sequential? x))
    (let [frst (first x)]
      (and
       (not (and (element? frst) (= 1 (count x))))
       (not (or (sequential? frst) (#{'do 'scope} frst)))))))

(defn results [x]
  (if (result? x)
    (if (vector? x) x (list x))
    (results (last x))))

(defn transform-results [f x]
  (loop [z (zip/seq-zip x)]
    (if (result? (zip/node z))
      (zip/root (zip/replace z (f (results x))))
      (recur (-> z zip/down zip/rightmost)))))

;;general program transformation

(defn param-dispatch [t]
  (cond
   (and (vector? t) (number? (first t))) :dim
   (number? t) :dim
   (vector? t) :elements
   (map? t) :params
   (symbol? t) :symbol
   (keyword? t) :keyword
   :else (println "Don't recognize" t (meta t))))

(defn process-operator [x params elements]
  (->> x
       (apply-transforms ;;tag elements and params
        (concat
         (map (fn [[k v]]
                (let [x (-> k name symbol)]
                  (replace-with x (add-meta x :tag (*typeof-param* v)))))
              params)
         (map (fn [[idx e]]
                   (let [type (*typeof-element* e)]
                     (fn [x]
                       (when (and (element? x) (= idx (element-index x)))
                         (add-meta x :tag type)))))
              (indexed elements))))
       transform-expr))

;;special map operators

(defvar- convolution-expr
  '(let [--half-dim (/ (dim :element) 2.0)
         --start    (max (float2 0.0) (- :coord (floor --half-dim)))
         --end      (min :dim (+ :coord (ceil --half-dim)))]
     (for [(<- i (.x --start)) (< i (.x --end)) (+= i 1.0)]
       (for [(<- j (.y --start)) (< j (.y --end)) (+= j 1.0)]
         (let [--location (float2 i j)
               --offset   (- --location :coord)
               --lookup   (:element (+ --location --half-dim))]
           :body)))))

(defmulti transform-convolution #(param-dispatch (second %)))

(defmethod transform-convolution :symbol [_ element & body]
  (let [body (apply-transforms
              (list
               (replace-with :offset '--offset)
               #(when (and (element? %) (symbol? %))
                  (if (= element %)
                    (list element '--lookup)
                    (list element '--location))))
              body)]
    (apply-transforms
     (list
      #(if (= :element %) element)
      #(if (= :body %) body))
     convolution-expr)))

(defmethod transform-convolution :dim [_ dim & body]
  (let [body (apply-transforms
              (list
               (replace-with :offset '--offset)
               #(when (and (element? %) (symbol? %))
                  '(% --location)))
              body)]
    (apply-transforms
     (list
      #(if (= :element %) '%)
      #(if (= :body %) body))
     convolution-expr)))

(defn- transform-dim [x]
  (let [idx (element-index (second x))]
    (add-meta (symbol (str "--dim" idx)) :tag (*typeof-dim* idx))))

;;defmap

(defmulti process-map (fn [& args] (->> args rest (map param-dispatch) vec)))

(defmethod process-map [:dim] [program dim]
  (process-map program {} [] dim))

(defmethod process-map [:elements] [program elements]
  (let [elements* (process-elements elements)]
    (process-map program {} elements* (*dim-element* (first elements*)))))

(defmethod process-map [:params :dim] [program params dim]
  (process-map program params [] dim))

(defmethod process-map [:params :elements] [program params elements]
  (let [elements* (process-elements elements)]
    (process-map program params elements* (*dim-element* (first elements*)))))

(defmethod process-map [:params :elements :dim] [program params elements dim]
  (let [num-elements (->> program
                          (tree-filter element?)
                          (map #(if (symbol? %) % (first %)))
                          distinct
                          count)]
    (doseq [[idx e] (indexed elements)]
      (if (nil? e)
        (throw (Exception. (str "Element at position " idx " is nil")))))
    (if (not= num-elements (count elements))
      (throw (Exception. (str "Expected " num-elements ", received " (count elements))))))
  (let [dim (if (number? dim) (rectangle dim) dim)
        yield-program (memoize
                       (fn []
                         (let [program (apply-transforms
                                        (list
                                         #(when (and (seq? %) (first= % 'dim)) (transform-dim %))
                                         #(when (and (seq? %) (first= % 'convolve)) (transform-convolution %)))
                                        program)]
                           (process-operator program params elements))))
        yield-results (memoize #(map typeof (results (yield-program))))]
    {:yield-program yield-program
     :yield-results yield-results
     :signature [(map *typeof-param* (vals params)) (map *typeof-element* elements)]
     :elements elements
     :params params
     :dim dim}))

;;defreduce

(defvar- reduce-program
  '(let [-source-coord (* (floor :coord) 2.0)
         -x (> (.x --bounds) (.x -source-coord))
         -y (> (.y --bounds) (.y -source-coord))]
     (<- -a (% -source-coord))
     (if -x
       (let [-b (% (+ -source-coord (float2 1.0 0.0)))
             -c -a]
         :expr))
     (if -y
       (let [-b (% (+ -source-coord (float2 0.0 1.0)))
             -c -a]
         :expr))
     (if (and -x -y)
       (let [-b (% (+ -source-coord (float2 1.0 1.0)))
             -c -a]
         :expr))
     -a))

(defmulti process-reduce (fn [& args] (->> args rest (map param-dispatch) vec)))

(defmethod process-reduce [:elements] [program elements]
  (process-reduce program {} elements))

(defmethod process-reduce [:params :elements] [program params elements]
  (let [elements (process-elements [elements])
        dim (*dim-element* (first elements))]
    (process-reduce program params elements dim)))

(defmethod process-reduce [:params :elements :dim] [program params elements dim]
  (let [yield-program (memoize
                       (fn []
                         (let [expr (apply-transforms
                                     (list
                                      (replace-with '%1 '-b)
                                      (replace-with '%  '-b)
                                      (replace-with '%2 '-c))
                                     program)]
                           (process-operator
                            (tree-map
                             (fn [x]
                               (when (= :expr x)
                                 (transform-results #(list '<- '-a %) expr)))
                             reduce-program)
                            params
                            elements))))
        yield-result  (memoize #(typeof (results program)))]
    {:yield-program yield-program
     :yield-result yield-result
     :signature [(map *typeof-param* params) (map *typeof-element* elements)]
     :params params
     :elements elements
     :dim dim}))