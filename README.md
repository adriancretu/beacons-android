## Android BLE beacon advertising library

Broadcasts Bluetooth Low Energy beacons directly from Android 5.0 or later, on supported devices. Consists of:

- Virtual Beacons implementations for:
    * Eddystone URL and UID
    * Eddystone EID, with automatic beacon refresh
    * iBeacon
- A restartable service that manages the virtual beacons and their state
- A persistence layer for saving the beacon properties and states.
- [Ephemeral URL](https://github.com/uriio/ephemeral-api) integration:
  * URL registration and updating
  * Automatic short URL periodic re-issuing, and beacon re-creation.

This library is used by the [Beacon Toy app](https://play.google.com/store/apps/details?id=com.uriio)

*CAREFUL* - the service will restore active beacons when it (re)starts, so be sure that you either stop or delete a beacon after you no longer need it, If your app crashes, the service may restart and bring the beacon back, so make sure you check what beacons are enabled and stop the ones that you no longer need. Besides freeing resources, every device has a maximum number of concurrent BLE broadcasters (around 4, more or less?), which means when that number is reached, new beacons will fail to start.

### Setup (Android Studio / Gradle)
* Import the uriio-lib module in your app's main project. In settings.gradle:
```
include ':my-app', ':uriio-lib'
project (":uriio-lib").projectDir = new File("../uriio/beacons-android/uriio-lib")
```
* Add dependency to the library in your app's build.gradle file:

```
compile project(':uriio-lib')
```

* In your main Application's onCreate() (or Activity, or Service), initialize the Beacons API:

```
Beacons.initialize(this, "<YOUR_API_KEY_HERE>");
```

## Creating new beacons
Use Beacons.add() to create a new beacon, passing in one of the following specializations.
After adding a beacon, if Bluetooth is on (or when it gets enabled), the beacon will try to start.
If there's an error starting a beacon, a broadcast is sent by the Beacons service. See the ```BeaconsService``` class for details.

### Eddystone URL
```
Beacons.add(new EddystoneURLSpec(url, mode, txPowerLevel, name));
```

### Eddystone UID
```
Beacons.add(new EddystoneUIDSpec(namespaceInstance, domain, mode, txPowerLevel, name));
```

### Eddystone EID
An EID beacon first needs to be registered, e.g:
```
registrationResult = EIDUtils.register(eidServer, mTemporaryKeyPair.getPublicKey(),
        mTemporaryKeyPair.getPrivateKey(), rotationExponent);
```
Using the registration result we can now add the EID beacon to the registry and start it:
```
Beacons.add(new EddystoneEIDSpec(registrationResult.getIdentityKey(), rotationExponent,
        registrationResult.getTimeOffset(), mode, txPowerLevel, name));
```

### Ephemeral URL
An ephemeral URL broadcasts an Eddystone URL beacon, but can also dynamically change the broadcasted URL (and also even the target URL)

Example for registering a new URL and on success, creating an Ephemeral URL beacon:

```
byte[] temporaryPublicKey = null; // lets the API create a new key-pair
// timeToLive is in seconds; use 0 for an initial non-ephemeral beacon
Beacons.uriio().registerUrl(url, temporaryPublicKey, new Beacons.OnResultListener<Url>() {
    @Override
    public void onResult(Url result, Throwable error) {
        if (null != result) {
            // URL registered. We can now add a new Ephemeral beacon!
            Beacons.add(new EphemeralURLSpec(result.getId(), result.getToken(),
                    result.getUrl(), timeToLive, mode, txPowerLevel, name));
        }
        else {
            showError(error);
        }
    }
});
```

The API will issue periodically new short URLs for broadcasting as an Eddystone URL beacon, according to the
timeToLive property. If the TTL is zero, the beacon is not periodically updated with a new URL.

To update the target URL (with or without the need to change the beacon's other properties), use:
```
// item is an existing UriioItem
Beacons.uriio().updateUrl(item.getUrlId(), item.getUrlToken(), url, new Beacons.OnResultListener<Url>() {
    @Override
    public void onResult(Url result, Throwable error) {
        if (null != result) {
            // URL target was updated. Save the new value to the local store. 
            // fields that are not modified should be passed back from the current item
            Beacons.editEphemeralURLBeacon(item, url, mode, txPowerLevel, timeToLive, name);
            mConfigActivity.onConfigFinished(false);
        }
        else {
            showError(error);
        }
    }
});
```

### iBeacon
Adding an iBeacon:
```
Beacons.add(new iBeaconSpec(uuid, major, minor, mode, txPowerLevel, name));
```

## Editing a beacon
To update details of a beacon, use the specialized ```Beacons.edit*()``` calls for now. Fields that you don't want to update should be read back from the item. A simpler approach is under way.

### Deleting a beacon
```Beacons.delete(id)```

### Changing a beacon's state
A beacon can be in one of three states: Active, Paused, or Stopped. Use ```Beacons.setState``` to change a beacon's state.

## Listing the beacons
Retrieve the list of active and paused beacons by calling ```Beacons.getActive```
Stopped beacons are saved to storage. To iterate over them, use ```Beacons.getStopped()``` to get a Cursor.
While iterating over the cursor you can call ```Storage.itemFromCursor()``` to get actual items. 

## Changelog
1.2 (May 22, 2016)
* API overhaul

1.0 (May 5 2016)
* Initial release