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
