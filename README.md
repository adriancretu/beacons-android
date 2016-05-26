## Android BLE beacon advertising library

Broadcasts Bluetooth Low Energy beacons directly from Android 5.0 or later, on supported devices. Consists of:

- Virtual Beacons implementations for:
    * Eddystone URL and UID
    * Eddystone EID, with automatic beacon refresh
    * iBeacon
- An Eddystone-GATT service and server, to allow remote EID registration via Bluetooth (e.g. with Beacon Tools).
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
// note - API Key is optional unless you need Ephemeral URL support
Beacons.initialize(this, "<YOUR_API_KEY_HERE>");
```

## Creating new beacons

All constructors support extra arguments to set their initial Advertise mode, TX power, and name.

```
Beacon urlBeacon = new EddystoneURL(url);
Beacon uidBeacon = new EddystoneUID(namespaceInstance);
Beacon iBeacon = new iBeacon(uuid, major, minor);
```

Use Beacons.add() to store and actually start a beacon.
After adding a beacon, if Bluetooth is on (or when it gets enabled), the beacon will try to start.
If there's an error starting a beacon, a broadcast is sent by the service. See the ```BleService``` class for details.

### Eddystone EID

An EID beacon first needs to be registered. You can fake a registration and use that to provision an EID beacon.
Realistically, you'll use the Eddystone-GATT feature to listen for a registration external tool (like Beacon Tools) that will provide the
EID registration details.
```
fakeRegistration = EIDUtils.register(new LocalEIDResolver(), mTemporaryKeyPair.getPublicKey(),
        mTemporaryKeyPair.getPrivateKey(), rotationExponent);

// using the registration result we can now add the EID beacon to the registry and start it:
Beacons.add(new EddystoneEID(registrationResult.getIdentityKey(), rotationExponent,
        registrationResult.getTimeOffset()));
```

### Eddystone-GATT usage

Eddystone-GATT will run a GATT server and can configure a Eddystone URL/UID/EID beacon.
You receive the final configured beacon in a callback after the owner disconnects.
Every beacon will store its own Lock Key, allowing re-configuration in future versions (since e.g. the Proximity API also keeps the Unlock Key, we must
keep a copy of it too, to allow GATT-based beacon unlocking).

```
mGattServer = new EddystoneGattServer(mPivotBeacon, new EddystoneGattServer.Listener() {
    @Override
    public void onGattFinished(EddystoneBase configuredBeacon) {
        if (null != configuredBeacon) {
            Beacons.add(configuredBeacon);
        }
    }
}, new Loggable() {
    @Override
    public void log(String tag, final String message) {
        // log however you need
    }
});

// start with an empty UID advertiser with default settings.
EddystoneUID currentBeacon = new EddystoneUID(new byte[16]);

mEditLog.setText(String.format("Unlock Key:\n%s\n", Util.binToHex(currentBeacon.getLockKey(), 0, 16, ' ')));;

mGattServer.start(mConfigActivity, currentBeacon);

// ... when you are done

mGattServer.close();
// this must be done last
Beacons.delete(mPivotBeacon.getId());

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
            Beacons.add(new EphemeralURL(result.getId(), result.getToken(),
                    result.getUrl(), timeToLive));
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
// item is an existing EphemeralURL
Beacons.uriio().updateUrl(item.getUrlId(), item.getUrlToken(), url, new Beacons.OnResultListener<Url>() {
    @Override
    public void onResult(Url result, Throwable error) {
        if (null != result) {
            // URL target was updated. Save the new value to the local store. 
            item.edit().setLongUrl(result.getUrl()).apply()
        }
        else {
            showError(error);
        }
    }
});
```

## Editing a beacon
General pattern to update one or more properties:

```
beacon.edit()
    .set*(value)
    .set*(value)
    .apply();
```

Beacon will save and restart as needed.


### Deleting a beacon

```
Beacons.delete(id)
```

### Changing a beacon's state
A beacon can be in one of three states: Active, Paused, or Stopped. Use ```Beacons.setState``` to change a beacon's state.

## Listing the beacons
Retrieve the list of active and paused beacons by calling ```Beacons.getActive```
Stopped beacons are saved to storage. To iterate over them, use ```Beacons.getStopped()``` to get a Cursor.
While iterating over the cursor you can call ```Storage.itemFromCursor()``` to get actual items. 
