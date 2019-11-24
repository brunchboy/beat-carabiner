(ns beat-carabiner.core
  "The main entry point for the beat-carabiner library."
  (:require [taoensso.timbre :as timbre])
  (:import [java.net Socket InetSocketAddress]
           [org.deepsymmetry.beatlink DeviceFinder DeviceAnnouncementListener BeatFinder
            VirtualCdj MasterListener DeviceUpdateListener LifecycleListener]
           [org.deepsymmetry.electro Metronome Snapshot]))

(def device-finder
  "Holds the singleton instance of the Device Finder for convenience."
  (DeviceFinder/getInstance))

(def virtual-cdj
  "Holds the singleton instance of the Virtual CDJ for convenience."
  (VirtualCdj/getInstance))

(def beat-finder
  "Holds the singleton instance of the Beat Finder for convenience."
  (BeatFinder/getInstance))

(defonce ^{:private true
           :doc "When connected, holds the socket used to communicate
  with Carabiner, the estimated latency in milliseconds between an
  actual beat played by a CDJ and when we receive the packet, values
  which track the peer count and tempo reported by the Ableton Link
  session and the target tempo we are trying to maintain (when
  applicable), and the `:running` flag which can be used to gracefully
  terminate that thread.

  The `:bar` entry controls whether the Link and Pioneer timelines
  should be aligned at the bar level (if true) or beat level.

  The `:last` entry is used to assign unique integers to each
  `:running` value as we are started and stopped, so a leftover
  background thread from a previous run can know when it is stale and
  should exit.)

 `:sync-mode` can be `:off`, `:passive` (meaning Link always follows
  the Pro DJ Link network, and we do not attempt to control other
  players on that network), or `:full` (bidirectional, determined by
  the Master and Sync states of players on the DJ Link network,
  including Beat Link's `VirtualCdj`).

  Once we are connected to Carabiner, the current Link session tempo
  will be available under the key `:link-bpm`.

  If we have been told to lock the Link tempo, there will be a
  `:target-bpm` key holding that tempo."}

  client (atom {:port 17000
                :latency   1
                :last      0
                :sync-mode :off}))

(def bpm-tolerance
  "The amount by which the Link tempo can differ from our target tempo
  without triggering an adjustment."
  0.00001)

(def skew-tolerance
  "The amount by which the start of a beat can be off without
  triggering an adjustment. This can't be larger than the normal beat
  packet jitter without causing spurious readjustments."
  0.0166)

(def connect-timeout
  "How long the connection attempt to the Carabiner daemon can take
  before we give up on being able to reach it."
  5000)

(def read-timeout
  "How long reads from the Carabiner daemon should block so we can
  periodically check if we have been instructed to close the
  connection."
  2000)

(defn state
  "Returns the current state of the Carabiner connection. Possible keys
  include:

  `:port`, the port on which the Carabiner daemon is listening.

  `:latency`, the estimated latency in milliseconds between an
  actual beat played by a CDJ and when we receive the packet.

  `:sync-mode`, which can be `:off`, `:passive` (meaning Link always follows
  the Pro DJ Link network, and we do not attempt to control other
  players on that network), or `:full` (bidirectional, determined by
  the Master and Sync states of players on the DJ Link network,
  including Beat Link's `VirtualCdj`).

  `:bar` determines whether the Link and Pioneer timelines should be
  synchronized at the level of entire measures (if present and
  `true`), or individual beats (if not).

  `:running` will have a non-`nil` value if we are connected to
  Carabiner. Once we are connected to Carabiner, the current Link
  session tempo will be available under the key `:link-bpm` and the
  number of Link peers under `:link-peers`.

  If we have been told to lock the Link tempo, there will be a
  `:target-bpm` key holding that tempo."
  []
  (select-keys @client [:port :latency :sync-mode :bar :running :link-bpm :link-peers :target-bpm]))

(defn active?
  "Checks whether there is currently an active connection to a
  Carabiner daemon."
  []
  (:running @client))

(defn sync-enabled?
  "Checks whether we have an active connection and are in any sync mode
  other than `:off`."
  []
  (let [state @client]
    (and (:running state)
         (not= :off (:sync-mode state)))))

(defn set-carabiner-port
  "Sets the port to be uesd to connect to Carabiner. Can only be called
  when not connected."
  [port]
  (when (active?)
    (throw (IllegalStateException. "Cannot set port when already connected.")))
  (swap! client assoc :port port))

(defn set-latency
  "Sets the estimated latency in milliseconds between an actual beat
  played on a CDJ and when we receive the packet."
  [latency]
  (swap! client assoc :latency latency))

(defn set-sync-bars
  "Sets whether we should synchronize the Ableton Link and Pioneer
  timelines at the level of entire measures, rather than simply
  individual beats."
  [bars?]
  (swap! client assoc :bar (boolean bars?)))

(defn- ensure-active
  "Throws an exception if there is no active connection."
  []
  (when-not (active?)
    (throw (IllegalStateException. "No active Carabiner connection."))))

(defn- send-message
  "Sends a message to the active Carabiner daemon."
  [message]
  (ensure-active)
  (let [output-stream (.getOutputStream (:socket @client))]
    (.write output-stream (.getBytes (str message "\n") "UTF-8"))
    (.flush output-stream)))

(defn- check-link-tempo
  "If we are supposed to master the Ableton Link tempo, make sure the
  Link tempo is close enough to our target value, and adjust it if
  needed. Otherwise, if the Virtual CDJ is the tempo master, set its
  tempo to match Link's."
  []
  (let [state      @client
        link-bpm   (:link-bpm state 0.0)
        target-bpm (:target-bpm state)]
    (if (some? target-bpm)
      (when (> (Math/abs (- link-bpm target-bpm)) bpm-tolerance)
        (send-message (str "bpm " target-bpm)))
      (when (and (.isTempoMaster virtual-cdj) (pos? link-bpm))
        (.setTempo virtual-cdj link-bpm)))))

(def ^{:private true
       :doc "Functions to be called with the updated client state
  whenever we have processed a status update from Carabiner."}

  status-listeners (atom #{}))

(defn add-status-listener
  "Registers a function to be called with the updated client state
  whenever we have processed a status update from Carabiner."
  [listener]
  (swap! status-listeners conj listener))

(defn remove-status-listener
  "Removes a function from the set that is called whenever we have
  processed a status update from Carabiner."
  [listener]
  (swap! status-listeners disj listener))

(defn- send-status-updates
  "Calls any registered status listeners with the current client state."
  []
  (when-let [listeners (seq @status-listeners)]
      (let [updated (state)]
        (doseq [listener listeners]
          (try
            (listener updated)
            (catch Throwable t
              (timbre/error t "Problem running status-listener.")))))))

(defn- handle-status
  "Processes a status update from Carabiner. Calls any registered status
  listeners with the resulting state, and performs any synchronization
  operations required by our current configuration."
  [status]
  (let [bpm (double (:bpm status))
        peers (int (:peers status))]
    (swap! client assoc :link-bpm bpm :link-peers peers)
    (send-status-updates))
  (check-link-tempo))

(defn- handle-beat-at-time
  "Processes a beat probe response from Carabiner."
  [info]
  (let [raw-beat (Math/round (:beat info))
        beat-skew (mod (:beat info) 1.0)
        [time beat-number] (:beat @client)
        candidate-beat (if (and beat-number (= time (:when info)))
                         (let [bar-skew (- (dec beat-number) (mod raw-beat 4))
                               adjustment (if (<= bar-skew -2) (+ bar-skew 4) bar-skew)]
                           (+ raw-beat adjustment))
                         raw-beat)
        target-beat (if (neg? candidate-beat) (+ candidate-beat 4) candidate-beat)]
    (when (or (> (Math/abs beat-skew) skew-tolerance)
              (not= target-beat raw-beat))
      (timbre/info "Realigning to beat" target-beat "by" beat-skew)
      (send-message (str "force-beat-at-time " target-beat " " (:when info) " 4.0")))))

(defn- handle-phase-at-time
  "Processes a phase probe response from Carabiner."
  [info]
  (let [state                            @client
        [ableton-now ^Snapshot snapshot] (:phase-probe state)
        align-to-bar                     (:bar state)]
    (if (= ableton-now (:when info))
      (let [desired-phase  (if align-to-bar
                             (/ (:phase info) 4.0)
                             (- (:phase info) (long (:phase info))))
            actual-phase   (if align-to-bar
                             (.getBarPhase snapshot)
                             (.getBeatPhase snapshot))
            phase-delta    (Metronome/findClosestDelta (- desired-phase actual-phase))
            phase-interval (if align-to-bar
                             (.getBarInterval snapshot)
                             (.getBeatInterval snapshot))
            ms-delta       (long (* phase-delta phase-interval))]
        (when (> (Math/abs ms-delta) 0)
          ;; We should shift the Pioneer timeline. But if this would cause us to skip or repeat a beat, and we
          ;; are shifting less 1/5 of a beat or less, hold off until a safer moment.
          (let [beat-phase (.getBeatPhase (.getPlaybackPosition virtual-cdj))
                beat-delta (if align-to-bar (* phase-delta 4.0) phase-delta)
                beat-delta (if (pos? beat-delta) (+ beat-delta 0.1) beat-delta)]  ; Account for sending lag.
            (when (or (zero? (Math/floor (+ beat-phase beat-delta)))  ; Staying in same beat, we are fine.
                      (> (Math/abs beat-delta) 0.2))  ; We are moving more than 1/5 of a beat, so do it anyway.
              (timbre/info "Adjusting Pioneer timeline, delta-ms:" ms-delta)
              (.adjustPlaybackPosition virtual-cdj ms-delta)))))
      (timbre/warn "Ignoring phase-at-time response for time" (:when info) "since was expecting" ableton-now))))

(def ^{:private true
       :doc "Functions to be called if we detect the a problematic
       version of Carabiner is running, so the user can be warned in
       some client-specific manner."}

  version-listeners (atom #{}))

(defn add-bad-version-listener
  "Registers a function to be called if we detect a problematic version
  of Carabiner is running, with a description of the problem to be
  presented to the user in some client-specific manner."
  [listener]
  (swap! version-listeners conj listener))

(defn remove-bad-version-listener
  "Removes a function from the set that is called when we detect a bad
  Carabiner version."
  [listener]
  (swap! version-listeners disj listener))

(defn- handle-version
  "Processes the response to a recognized version command. Warns if
  Carabiner should be upgraded."
  [version]
  (timbre/info "Connected to Carabiner daemon, version:" version)
  (when (= version "1.1.0")
    (timbre/warn "Carabiner needs to be upgraded to at least version 1.1.1 to avoid sync glitches.")
    (doseq [listener (@version-listeners)]
      (try
        (listener "You are running an old version of Carabiner, which cannot
properly handle long timestamps. You should upgrade to at least
version 1.1.1, or you might experience synchronization glitches.")
        (catch Throwable t
          (timbre/error t "Problem running bad-version-listener."))))))

(defn- handle-unsupported
  "Processes an unsupported command reponse from Carabiner. If it is to
  our version query, warn the user that they should upgrade Carabiner."
  [command]
  (if (= command 'version)
    (do
      (timbre/warn "Carabiner needs to be upgraded to at least version 1.1.1 to avoid multiple problems.")
      (doseq [listener (@version-listeners)]
        (try
          (listener "You are running an old version of Carabiner, which might lose messages.
You should upgrade to at least version 1.1.1, which can cope with
multiple commands being grouped in the same network packet (this
happens when they are sent near the same time), and can properly parse
long timestamp values. Otherwise you might experience synchronization
glitches.")
          (catch Throwable t
            (timbre/error t "Problem running bad-version-listener.")))))
    (timbre/error "Carabiner complained about not recognizing our command:" command)))

(def ^{:private true
       :doc "Functions to be called when we close our Carabiner
  connection, so clients can take whatever action they need. The
  function is passed an argument that will be `true` if the
  disconnection was unexpected."}

  disconnection-listeners (atom #{}))

(defn add-disconnection-listener
  "Registers a function to be called when we close our Carabiner
  connection, so clients can take whatever action they need. The
  function is passed an argument that will be `true` if the
  disconnection was unexpected."
  [listener]
  (swap! disconnection-listeners conj listener))

(defn remove-disconnection-listener
  "Removes a function from the set that is called when close our
  Carabiner connection."
  [listener]
  (swap! disconnection-listeners disj listener))

(defn- response-handler
  "A loop that reads messages from Carabiner as long as it is supposed
  to be running, and takes appropriate action."
  [socket running]
  (let [unexpected? (atom false)]  ; Tracks whether Carabiner unexpectedly closed the connection from its end.
    (try
      (let [buffer      (byte-array 1024)
            input       (.getInputStream socket)]
        (while (and (= running (:running @client)) (not (.isClosed socket)))
          (try
            (let [n (.read input buffer)]
              (if (and (pos? n) (= running (:running @client)))  ; We got data, and were not shut down while reading
                (let [message (String. buffer 0 n "UTF-8")
                      reader  (java.io.PushbackReader. (clojure.java.io/reader (.getBytes message "UTF-8")))]
                  (timbre/debug "Received:" message)
                  (loop [cmd (clojure.edn/read reader)]
                    (case cmd
                      status        (handle-status (clojure.edn/read reader))
                      beat-at-time  (handle-beat-at-time (clojure.edn/read reader))
                      phase-at-time (handle-phase-at-time (clojure.edn/read reader))
                      version       (handle-version (clojure.edn/read reader))
                      unsupported   (handle-unsupported (clojure.edn/read reader))
                      (timbre/error "Unrecognized message from Carabiner:" message))
                    (let [next-cmd (clojure.edn/read {:eof ::eof} reader)]
                      (when (not= ::eof next-cmd)
                        (recur next-cmd)))))
                (do  ; We read zero, means the other side closed; force our loop to terminate.
                  (.close socket)
                  (reset! unexpected? true))))
            (catch java.net.SocketTimeoutException e
              (timbre/debug "Read from Carabiner timed out, checking if we should exit loop."))
            (catch Throwable t
              (timbre/error t "Problem reading from Carabiner.")))))
      (timbre/info "Ending read loop from Carabiner.")
      (swap! client (fn [oldval]
                      (if (= running (:running oldval))
                        (dissoc oldval :running :socket :link-bpm :link-peers)  ; We are causing the ending.
                        oldval)))  ; Someone else caused the ending, so leave client alone; may be new connection.
      (.close socket)  ; Either way, close the socket we had been using to communicate, and update the window state.
      (doseq [listener @disconnection-listeners]
        (try
          (listener @unexpected?)
          (catch Throwable t
            (timbre/error t "Problem running disconnection-listener"))))
      (catch Throwable t
        (timbre/error t "Problem managing Carabiner read loop.")))))

(defn disconnect
  "Shut down any active Carabiner connection. The run loop will notice
  that its run ID is no longer current, and gracefully terminate,
  closing its socket without processing any more responses."
  []
  (swap! client dissoc :running :socket :link-bpm :link-peers))

(defn connect
  "Try to establish a connection to Carabiner. Returns truthy if the
  initial open succeeded. Sets up a background thread to reject the
  connection if we have not received an initial status report from the
  Carabiner daemon within a second of opening it.

  If `failure-fn` is supplied, it will be called with an explanatory
  message if the connection could not be established, so the user can
  be informed in an appropriate way."
  [failure-fn]
  (swap! client (fn [oldval]
                  (if (:running oldval)
                    oldval
                    (try
                      (let [socket (java.net.Socket.)
                            running (inc (:last oldval))]
                        (.connect socket (java.net.InetSocketAddress. "127.0.0.1" (:port oldval)) connect-timeout)
                        (.setSoTimeout socket read-timeout)
                        (future (response-handler socket running))
                        (merge oldval {:running running
                                       :last running
                                       :socket socket}))
                      (catch Exception e
                        (timbre/warn e "Unable to connect to Carabiner")
                        (try
                          (failure-fn "Unable to connect to Carabiner; make sure it is running on the specified port.")
                          (catch Throwable t
                            (timbre/error t "Problem running failure-fn")))
                        oldval)))))
  (when (active?)
    (future
      (Thread/sleep 1000)
      (if (:link-bpm @client)
        (do  ; We are connected! Check version and configure for start/stop sync.
          (send-message "version")  ; Probe that a recent enough version is running.
          (send-message "enable-start-stop-sync"))  ; Set up support for start/stop triggers.
        (do  ; We failed to get a response, maybe we are talking to the wrong process.
          (timbre/warn "Did not receive inital status packet from Carabiner daemon; disconnecting.")
          (try
            (failure-fn
             "Did not receive expected response from Carabiner; is something else running on the specified port?")
            (catch Throwable t
              (timbre/error t "Problem running failure-fn")))
          (disconnect)))))
  (active?))

(defn valid-tempo?
  "Checks whether a tempo request is a reasonable number of beats per
  minute. Link supports the range 20 to 999 BPM. If you want something
  outside that range, pick the closest multiple or fraction; for
  example for 15 BPM, propose 30 BPM."
  [bpm]
  (< 20.0 bpm 999.0))

(defn- validate-tempo
  "Makes sure a tempo request is a reasonable number of beats per
  minute. Coerces it to a double value if it is in the legal Link
  range, otherwise throws an exception."
  [bpm]
  (if (valid-tempo? bpm)
    (double bpm)
    (throw (IllegalArgumentException. "Tempo must be between 20 and 999 BPM"))))

(defn lock-tempo
  "Starts holding the tempo of the Link session to the specified
  number of beats per minute."
  [bpm]
  (swap! client assoc :target-bpm (validate-tempo bpm))
  (send-status-updates)
  (check-link-tempo))

(defn unlock-tempo
  "Allow the tempo of the Link session to be controlled by other
  participants."
  []
  (swap! client dissoc :target-bpm)
  (send-status-updates))

(defn beat-at-time
  "Find out what beat falls at the specified time in the Link
  timeline, assuming 4 beats per bar since we are dealing with Pro DJ
  Link, and taking into account the configured latency. When the
  response comes, if we are configured to be the tempo master, nudge
  the Link timeline so that it had a beat at the same time. If a
  beat-number (ranging from 1 to the quantum) is supplied, move the
  timeline by more than a beat if necessary in order to get the Link
  session's bars aligned as well."
  ([time]
   (beat-at-time time nil))
  ([time beat-number]
   (let [adjusted-time (- time (* (:latency @client) 1000))]
     (swap! client assoc :beat [adjusted-time beat-number])
     (send-message (str "beat-at-time " adjusted-time " 4.0")))))

(defn start-transport
  "Tells Carabiner to start the Link session playing, for any
  participants using Start/Stop Sync. If `time` is supplied, it
  specifies when, on the Link microsecond timeline, playback should
  begin; the default is right now."
  ([]
   (start-transport (long (/ (System/nanoTime) 1000))))
  ([time]
   (send-message (str "start-playing " time))))

(defn stop-transport
  "Tells Carabiner to stop the Link session playing, for any
  participants using Start/Stop Sync. If `time` is supplied, it
  specifies when, on the Link microsecond timeline, playback should
  end; the default is right now."
  ([]
   (stop-transport (long (/ (System/nanoTime) 1000))))
  ([time]
   (send-message (str "stop-playing " time))))

(defn- align-pioneer-phase-to-ableton
  "Send a probe that will allow us to align the Virtual CDJ timeline to
  Ableton Link's."
  []
  (let [ableton-now (+ (long (/ (System/nanoTime) 1000)) (* (:latency @client) 1000))
        snapshot    (.getPlaybackPosition virtual-cdj)]
    (swap! client assoc :phase-probe [ableton-now snapshot])
    (send-message (str "phase-at-time " ableton-now " 4.0"))))

(defonce ^{:private true
           :doc "Responds to tempo changes and beat packets from the
  master player when we are controlling the Ableton Link tempo (in
  Passive or Full mode)."}
  master-listener
  (reify MasterListener

    (masterChanged [this update])  ; Nothing we need to do here, we don't care which device is the master.

    (tempoChanged [this tempo]
      (if (valid-tempo? tempo)
        (lock-tempo tempo)
        (unlock-tempo)))

    (newBeat [this beat]
      (try
        (when (and (.isRunning virtual-cdj) (.isTempoMaster beat))
          (beat-at-time (long (/ (.getTimestamp beat) 1000))
                        (when (:bar @client) (.getBeatWithinBar beat))))
        (catch Exception e
          (timbre/error e "Problem responding to beat packet in Carabiner."))))))

(defn- tie-ableton-to-pioneer
  "Start forcing the Ableton Link to follow the tempo and beats (and
  maybe bars) of the Pioneer master player."
  []
  (.addMasterListener virtual-cdj master-listener)
  (.tempoChanged master-listener (.getMasterTempo virtual-cdj)))

(defn- free-ableton-from-pioneer
  "Stop forcing Ableton Link to follow the Pioneer master player."
  []
  (.removeMasterListener virtual-cdj master-listener)
  (unlock-tempo))

(defn- tie-pioneer-to-ableton
  "Start forcing the Pioneer tempo and beat grid to follow Ableton
  Link."
  []
  #_(timbre/info (Exception.) "tie-pioneer-to-ableton called!")
  (free-ableton-from-pioneer)  ; When we are master, we don't follow anyone else.
  (align-pioneer-phase-to-ableton)
  (.setTempo virtual-cdj (:link-bpm @client))
  (.becomeTempoMaster virtual-cdj)
  (.setPlaying virtual-cdj true)
  (future  ; Realign the BPM in a millisecond or so, in case it gets changed by the outgoing master during handoff.
    (Thread/sleep 1)
    (send-message "status")))

(defn- free-pioneer-from-ableton
  "Stop forcing the Pioneer tempo and beat grid to follow Ableton Link."
  []
  (.setPlaying virtual-cdj false)
  ;; If we are also supposed to be synced the other direction, it is time to turn that back on.
  (when (and (#{:passive :full} (:sync-mode @client))
             (.isSynced virtual-cdj))
    (tie-ableton-to-pioneer)))

(defn sync-link
  "Controls whether the Link session is tied to the tempo of the DJ Link
  devices. Also reflects that in the sync state of the `VirtualCdj` so
  it can be seen on the DJ Link network, and if our Sync mode is
  Passive or Full, unless we are the tempo master, start tying the
  Ableton Link tempo to the Pioneer DJ Link tempo master. Has no
  effect if we are not in a compatible sync mode."
  [sync?]
  (when (not= (.isSynced virtual-cdj) sync?)
    (.setSynced virtual-cdj sync?))
  (when (and (#{:passive :full} (:sync-mode @client))
             (not (.isTempoMaster virtual-cdj)))
    (if sync?
      (tie-ableton-to-pioneer)
      (free-ableton-from-pioneer))))

(defn link-master
  "Controls whether the Link session is tempo master for the DJ link
  devices. Has no effect if we are not in a compatible sync mode."
  [master?]
  (if master?
    (do
      (when (= :full (:sync-mode @client))
        (tie-pioneer-to-ableton)))
    (free-pioneer-from-ableton)))

(defn set-sync-mode
  "Validates that the desired mode is consistent with the current state,
  and if so, updates our tracking atom and performs any necessary
  synchronization operations."
  [new-mode]
  (cond
    (and (not= new-mode :off) (not (.isRunning virtual-cdj)))
    (throw (IllegalStateException. "Cannot synchronize when VirtualCdj isn't running."))

    (and (= new-mode :full) (not (.isSendingStatus virtual-cdj)))
    (throw (IllegalStateException. "Cannot use full sync mode when VirtualCdj isn't sending status packets.")))

  (swap! client assoc :sync-mode new-mode)
  (if ({:passive :full} new-mode)
    (do
      (sync-link (.isSynced virtual-cdj))  ; This is now relevant, even if it wasn't before.
      (if (and (= :full new-mode) (.isTempoMaster virtual-cdj))
        (tie-pioneer-to-ableton)))
    (do
      (free-ableton-from-pioneer)
      (free-pioneer-from-ableton))))

(defn set-link-tempo
  "Sets the Link session tempo to the specified value, unless it is
  already close enough."
  [tempo]
  (when (> (Math/abs (- tempo (:link-bpm @client))) 0.005)
    (send-message (str "bpm " tempo))))


;; TODO: DELETE ME ONCE EVERYTHING NEEDED IS EXTRICATED.
#_(defn -main
  "The entry point when invoked as a jar from the command line. Parse
  options, and start daemon operation."
  [& args]
  ;; Start the daemons that do everything!
  (let [bar-align (not (:beat-align options))]
    (.addMasterListener   ; First set up to respond to master tempo changes and beats.
     virtual-cdj
     (reify MasterListener
       (masterChanged [_ update]
         #_(timbre/info "Master Changed!" update)
         (when (nil? update)           ; If there's no longer a tempo master,
           (carabiner/unlock-tempo)))  ; free the Ableton Link session tempo.
       (tempoChanged [_ tempo]  ; Master tempo has changed, lock the Ableton Link session to it, unless out of range.
         (if (carabiner/valid-tempo? tempo)
           (carabiner/lock-tempo tempo)
           (carabiner/unlock-tempo)))
       (newBeat [_ beat]  ; The master player has reported a beat, so align to it as needed.
         #_(timbre/info "Beat!" beat)
         (carabiner/beat-at-time (long (/ (.getTimestamp beat) 1000)) (when bar-align (.getBeatWithinBar beat)))))))


  (.addLifecycleListener
   virtual-cdj
   (reify LifecycleListener
     (started [_ sender])
     (stopped [_ sender]
       (carabiner/unlock-tempo))))

    (.start beat-finder)  ; Also start watching for beats, so the beat-alignment handler will get called.

  ;; Enter an infinite loop attempting to connect to the Carabiner daemon.
  (loop [port    (:carabiner-port options)
         latency (:latency options)]
    (timbre/info "Trying to connect to Carabiner daemon on port" port "with latency" latency)
    (carabiner/connect port latency)
    (timbre/warn "Not connected to Carabiner. Waiting ten seconds to try again.")
    (Thread/sleep 10000)
    (recur port latency)))
