;   Copyright (c) Zachary Tellman. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns penumbra.interface.slate
  (:use [clojure.contrib.def :only (defmacro-)])
  (:use [clojure.contrib.lazy-seqs :only (primes)])
  (:use [penumbra.opengl core geometry])
  (:import (java.util.concurrent Semaphore))
  (:import (javax.media.opengl
              GLPbuffer GLDrawableFactory GLEventListener
              GLCapabilities GLProfile GLAutoDrawable)))

;;;;;;;;;;;;;;;;

(gl-import glGenFramebuffers gl-gen-frame-buffers)
(gl-import glBindFramebuffer gl-bind-frame-buffer)
(gl-import glCheckFramebufferStatus gl-check-frame-buffer-status)

(defn gen-frame-buffer []
  (let [a (int-array 1)]
    (gl-gen-frame-buffers 1 a 0)
    (nth a 0)))

(defn bind-frame-buffer [fb]
  (gl-bind-frame-buffer :framebuffer fb))

;;;;;;;;;;;;;;;;;

(defn- prime-factors
  "returns prime factors of a number"
  ([n] (prime-factors primes [] n))
  ([primes factors n]
	 (let [p (first primes)]
	   (cond
		 (= n 1) factors
		 (zero? (rem n p)) (recur primes (conj factors p) (/ n p))
		 :else (recur (rest primes) factors n)))))

(defn rectangle [n]
  (let [factors   (prime-factors n)
        reordered (take (count factors) (interleave factors (reverse factors)))
        sqrt      (int (Math/sqrt n))
        divisor   (reduce #(if (>= sqrt (* %1 %2)) (* %1 %2) %1) 1 reordered)]
    [divisor (/ n divisor)]))

;;;;;;;;;;;;;;;;;

(defstruct slate-struct :p-buffer :queue)

(defn repaint [slate]
  (.repaint #^GLPbuffer (:p-buffer slate)))

(defn destruct [slate]
  (.destroy #^GLPbuffer (:p-buffer slate)))

(defn enqueue [slate f]
  (dosync
    (alter (:queue slate)
      #(concat % (list f))))
  (repaint slate))

(defn execute [slate]
  (let [coll @(:queue slate)]
    (doseq [f coll]
      (push-matrix (f)))
    (dosync
      (alter (:queue slate)
        #(drop (count coll) %)))))

(defn invoke [slate f]
  (let [#^Semaphore s (Semaphore. 1)]
    (.acquire s)
    (enqueue slate (fn [] (f) (.release s)))
    (.acquire s)))

(defn create-slate
  ([size-or-tex]
    (if (number? size-or-tex)
      (apply create-slate (rectangle size-or-tex))
      (create-slate (:width size-or-tex) (:height size-or-tex))))
  ([width height]
    (let
      [profile (GLProfile/get GLProfile/GL2GL3)
       cap (GLCapabilities. profile)]

      ;cap stuff goes here

      (let [p-buffer  (.. (GLDrawableFactory/getFactory profile) (createGLPbuffer cap nil width height nil))
            slate     (struct-map slate-struct :p-buffer p-buffer :queue (ref '()))]

        (doto p-buffer
          (.addGLEventListener
            (proxy [GLEventListener] []

              (display [drawable]
                (bind-gl drawable
                  (execute slate)))

              (reshape [#^GLAutoDrawable drawable x y width height]
                (bind-gl drawable
                  (viewport 0 0 width height)
                  (ortho-view 0 0 width height)))

              (init [#^GLAutoDrawable drawable]
                (bind-gl drawable
                  (bind-frame-buffer (gen-frame-buffer)))))))
        slate))))

(defmacro with-slate
  [slate & body]
    `(invoke ~slate (fn [] ~@body)))

(defmacro with-blank-slate
  [& body]
  `(with-slate (create-slate 1 1)
    ~@body))

