### 1.5.2 (January 3, 2018)
* New generic **offline** dynamic Eddystone-URL ephemeral beacon type, using **EID** strategy
* Schedule beacon refresh using device elapsed real-time, instead of unreliable system time
* Fix Eddystone-TLM battery temperature value
* Fix wrong "feature not supported" error being sent out when Bluetooth was just disabled
* "Advertiser added" event was not sent when enabling the first beacon, if Bluetooth was off
* NPE crash fix, for Android 5.0+ devices with no Bluetooth adapter
* Don't lose track of a started beacon when service wasn't yet started and no active beacons are stored.
* Android Oreo notification channel

### 1.5.1 (January 17, 2017)
* **Eddystone-TLM** support - broadcasts device's battery temperature, voltage, service uptime, and estimated PDU count since service started. Because Android allocates a new MAC randomly when TLM advertisement changes, every new telemetry will be seen as a new beacon.
* Fix GATT crash if Bluetooth was disabled when GATT server tried to start
* Removed legacy URIBeacon supporting code, replaced with optimized Eddystone-URL encoder and decoder
* Internal refactorings for better beacon and advertiser construction

### 1.5.0 (January 10, 2017)
* Updated storage layer to use a single table for keeping all beacon kinds
* Support for external beacon kinds (declared in app manifest so they can be loaded on boot / service restarts)
* Added generic BLE advertiser that accepts supported Android data types needed for advertising.
* Check explicitly for Lollipop when trying to start a beacon, to avoid crashes due to missing API calls
* Set beacon state to **paused** when it fails to advertise
* Add permission removal directives to manifest for a few automatically added sensitive permissions
* Moved UriIO custom dynamic beacon kind to the UriIO client library, where it always belonged

### 1.4.4 (January 2, 2017)
* Fix crash when Eddystone-URL has no payload
* Update and fix build environment if OSS repository default credentials don't exist

### 1.4.3 (October 22, 2016)
* Handle some obscure NPE cases
* Use Android API level 25 build tools

### 1.4.2 (September 27, 2016)
* Beacon persistence is now optional
* API changes:
    - `Beacons.enable/pause/stop/delete` is now simply `beacon.start()/pause()/stop()/delete()`
    - `Beacons.add(beacon)` is now `beacon.save()` (save and start the beacon)

### 1.4.1 (September 24, 2016)
* BLE service started when first active beacon started
* Don't clear API instance when service stops, to avoid crashes during context lifecycle

### 1.4.0 (September 20, 2016)
* BLE service improvements, self stopping, foreground service
* Imposed running beacons notification, with action buttons
* Fixed "advertise failed" when BT adapter name is longer than 8 characters
* Moved UriIO aphemeral URL beacons feature to separate library
* Improved API

### 1.3.4 (September 18, 2016)
* Simplified Eddystone-GATT significantly:
    - a single, optional beacon can be initially provided to be used as both connectable and as configuration target
    - automatically save the (new) configured beacon after configuration ends

### 1.3.3 (September 17, 2016)
* Updated dependencies, no code changes

### 1.3.2 (May 28, 2016)
* Fix some persistence bugs.

### 1.3.1 (May 27, 2016)
* Added Eddystone GATT configuration service, with Locking support and single-client mode.
* Smart property-based beacon editing with self-restart and saving when needed
* Merged beacon models from previous API into single beacon types.

### 1.2.0 (May 22, 2016)
* API updates to allow adding Eddystone and iBeacon with one-liners.

### 1.1.0
* Creating new Eddystone EID beacons now follows the spec more closely, using temporary Curve25519 keys for registration step.
* EID beacons will now refresh themselves according to their timer interval.

### 1.0.0 (May 5 2016)
Initial release!
