(ns app.components.grid
  (:require-macros [app.logging :refer [log]])
  (:require [reagent.core :as r]
            [re-frame.core :refer [dispatch subscribe]]
            [clojure.string :refer [join lower-case]]
            [app.util :as u]
            [app.colors :as c]
            [app.components.color-picker :as cp]))

(defn build-cursor [square across?]
  "Convert square into a cursor -- it knows which way it is oriented"
  {:square square :across? across?})

(defonce cursor-atom (r/atom (build-cursor nil true)))

(defn class-list [classes]
  "Utility for conditional CSS classes"
  (join " " (for [[class include?] classes :when include?] class)))

(defn build-square [col row]
  "Construct a square of column and row"
  {:col col :row row})

(defn squares-in-word [clue]
  "Construct squares for each column and row of an answer"
  (let [across? (:across? clue)
        length (count (:answer clue))
        start-row (:start-row clue)
        start-col (:start-col clue)
        row-count (if across? 1 length)
        col-count (if across? length 1)]
    (for [row (range start-row (+ start-row row-count))
          col (range start-col (+ start-col col-count))]
      (build-square col row))))

(defn square-in-word? [square clue]
  "Checks if a square is part of a word"
  (contains? (set (squares-in-word clue)) square))

(defn words-containing-square-inner [square clues]
  "Get all the words that contain a square"
  (filter #(square-in-word? square %) clues))

(def words-containing-square
  (memoize words-containing-square-inner))

(defn selected-word [cursor clues]
  (->> (words-containing-square (:square cursor) clues)
       (filter #(= (:across? %) (:across? cursor)))
       (first)))

(defn user-input [cursor clues game-state]
  (map #(get game-state %) (squares-in-word (selected-word cursor clues))))

(defn direction-allowed? [cursor clues]
  (some #(= (:across? cursor) (:across? %))
        (words-containing-square (:square cursor) clues)))

(defn word-correct? [clue game-state]
  "Construct a vector of letters paired with correct letters and ensure they match for the current game-state"
  (let [squares (squares-in-word clue)
        squares-with-correct-letters (map vector squares (:answer clue))]
    (every? (fn [[square correct-letter]]
              (=
                (get-in game-state [(keyword (u/marshal-square square)) :letter])
                (lower-case correct-letter)))
            squares-with-correct-letters)))

(defn square-correct? [square clues game-state]
  "Return true if each square in a word is solved"
  (some #(word-correct? % game-state) (words-containing-square square clues)))

(defn puzzle-complete? [puzzle game-state]
  "Return true if every word has been solved"
  (if (nil? puzzle)
    false
    (every? #(word-correct? % game-state) (:clues puzzle))))

(defn update-cursor
  "Toggles the cursor orientation when a square is clicked"
  [square clues]
    (let [scrollX js/window.scrollX
          scrollY js/window.scrollY
          input (.getElementById js/document "word-input")
          old-cursor @cursor-atom
          same-location (= square (:square old-cursor))
          old-across? (:across? old-cursor)
          flipped-across? (if same-location (not old-across?) old-across?)
          new-across? (if (direction-allowed? (build-cursor square flipped-across?) clues)
                        flipped-across? (not flipped-across?))
          cell (.getBoundingClientRect (.getElementById js/document (str "c" (:col square) "r" (:row square))))]

      ;; update cursor orientation (going across or going down, when possible)
      (reset! cursor-atom (build-cursor square new-across?))

      ;; this moves the hidden input to the position of the square, adjusting for scroll position
      (.setAttribute input "style" (str "top:" (+ (aget cell "top") scrollY) "px;left:" (+ (aget cell "left") js/window.scrollX) "px;"))

      ;; this focuses the hidden input, to the user it appears as if the square is focused
      (.focus input)))

(defn valid-cursor-position? [square grid]
  "Check if the cursor position exists on the grid"
  (let [row (:row square)
        col (:col square)
        grid-keys (map #(keyword (str %)) [row col])]
    (not (nil? (get-in grid grid-keys)))))

(defn transform-cursor [col-transform row-transform cursor puzzle]
  "Create new cursor based on provided transform functions"
  (let [grid (:grid puzzle)
        clues (:clues puzzle)
        row (:row (:square cursor))
        col (:col (:square cursor))
        across? (:across? cursor)
        new-square (build-square (col-transform col)
                                 (row-transform row))
        new-across? (if (direction-allowed? (build-cursor new-square across?) clues)
                      across? (not across?))]

    ;; don't create a new cursor if it's gonna go off the grid
    (if (valid-cursor-position? new-square grid)
      (build-cursor new-square new-across?)
      cursor)))

(defn next-cursor [cursor puzzle]
  "Construct a new cursor to move forwards"
  (let [across? (:across? cursor)
        row-transform (if across? identity inc)
        col-transform (if across? inc identity)]
    (transform-cursor col-transform row-transform cursor puzzle)))

(defn prev-cursor [cursor puzzle]
  "Construct a new cursor to move backwards"
  (let [across? (:across? cursor)
        row-transform (if across? identity dec)
        col-transform (if across? dec identity)]
    (transform-cursor col-transform row-transform cursor puzzle)))

(defn get-theme [user-id]
  "Get color theme for a given user id"
  (let [ulist (subscribe [:user-list])]
       (get-in @ulist [(keyword user-id) :color-scheme])))

(defn get-styles [theme]
  "Get the style attributes for a given color theme"
    (get c/colors (keyword theme)))

(defn handle-change [e]
  "Update game state when user inputs a letter or removes a letter"
  (let [puzzle @(subscribe [:puzzle])
        game-state (subscribe [:game-state])
        cursor @cursor-atom
        clues (:clues puzzle)
        prev-word (join " " (user-input cursor clues @game-state))
        word (clojure.string/lower-case (.. e -target -value))
        cur-square (:square cursor)
        correct? (square-correct? cur-square clues @game-state)]

    (.preventDefault e)

    (if (> (count word) (count prev-word))
      ;; we're moving forwards, update cursor and state
      (when (re-matches #"[a-zA-Z]" (join "" (last word)))
        ;; we have a letter
        (swap! cursor-atom next-cursor puzzle)
        (if (not correct?) (dispatch [:send-move [cur-square (last word)]])))

      ;; we're moving backwards, update cursor and remove letter from state
      (do
        (swap! cursor-atom prev-cursor puzzle)
        (if (not correct?) (dispatch [:send-move [cur-square nil]]))))))

(defn crossword-table [puzzle cursor game-state]
  "Construct the grid based on current puzzle, cursor position, and game state"
  (let [grid (:grid puzzle)
        clues (:clues puzzle)
        grid-size (:grid-size puzzle)
        active-word (selected-word cursor clues)]

    (defn crossword-table-cell [col-idx row-idx cell]
      "Construct a table cell based on position and game state"
      (if (nil? cell)
        [:div.cell.cell--empty]
        (let [square (build-square col-idx row-idx)
              classes (class-list {"selected" (= square (:square cursor))
                                   "active" (square-in-word? square active-word)
                                   "correct" (square-correct? square clues game-state)})
              click-handler (fn [e] (update-cursor square clues))
              styles (get-styles (get-theme (get-in game-state [(keyword (u/marshal-square square)) :user])))]

          [:div.cell {:id (str "c" col-idx "r" row-idx) :on-click click-handler :class classes :style styles}
           (when-let [letter (get-in game-state [(keyword (u/marshal-square square)) :letter])] [:span letter])
           (if-let [number (:number cell)]
             [:span.clue-number number])])))

    (defn crossword-table-row [row-idx row]
      "Construct a row of cells based on grid size"
      (let [cells (->> (range 0 grid-size)
                       (map #(get row (keyword (str %)))))]
        [:div.row
         (for [[idx cell] (map-indexed vector cells)]
           ^{:key idx} [crossword-table-cell idx row-idx cell])]))

    (let [rows (->> (range 0 grid-size)
                    (map #(get grid (keyword (str %)))))]
      [:div
       ;; this input is hidden and is bound to the value of the selected word
       [:input {:id "word-input"
                :value (user-input cursor clues game-state)
                :on-change handle-change}]
       [:div#crossword-table
        (for [[idx row] (map-indexed vector rows)]
          ^{:key idx} [crossword-table-row idx row])]])))

(defn crossword-clue [puzzle cursor]
  (let [clue (selected-word cursor (:clues puzzle))
        clue-number (:number clue)
        clue-text (:clue clue)
        clue-length (count (:answer clue))]
    [:div.clue {:style {:visibility (if clue "" "hidden")}} [:p.f6 (str clue-number ". " clue-text)]]))

(defn user-list [users]
  [:div.tc.center.mw6
   [:h3.ttu.tracked.fw1.mb2 "Players: "]
   (for [user users] ^{:key (:uid user)}
     [:div.dib.relative.mb3
      [:div.w3.h3.mr3.br-100 {:style {:background (str "url(" (:image user) ")") :opacity 0.6}}]
      [:div.user-list-scores {:style (get-styles (:color-scheme user))} (str (:score user))]])])

(defn main []
  (let [puzzle (subscribe [:puzzle])
        cursor @cursor-atom
        game-state (subscribe [:game-state])
        users (subscribe [:user-list])]
      [:div.crossword-player
       [user-list (vals @users)]
       [:div
        (if (puzzle-complete? @puzzle @game-state) [:h3.f3.tc.solved "Puzzle solved!"])
        [crossword-clue @puzzle cursor]
        [crossword-table @puzzle cursor @game-state]
        [crossword-clue @puzzle cursor]
        [cp/main]]]))
