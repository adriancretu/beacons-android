## Android BLE beacon advertising library

- [Description](#description)
- [Features](#features)
- [Setup](#setup)
- [Creating beacons](#creating-beacons)
    * Eddystone-URL / Eddystone-UID / iBeacon
    * [Eddystone-EID](#eddystone-eid)
    * [Eddystone-GATT service](#eddystone-gatt)
- [Ephemeral URLs](#ephemeral-urls)
    * [Registering a redirected long URL](#registering-a-redirected-long-url)
    * [Updating the target URL](#updating-the-target-url)
- [Editing beacons](#editing-beacons)
    * [Change properties](#change-properties)
    * [Deleting a beacon](#deleting-a-beacon)
    * [Listing the beacons](#listing-the-beacons)
    
#### What this library does:
Allows your app to broadcast as a Bluetooth beacon, as explained below.
#### What this library **doesn't** do:
This is not a *beacon scanning* library. Please use either the Nearby API, or (for advanced use-cases) [OneBeacon](https://github.com/Codefy/onebeacon-android) for that.

### Description

Broadcast Bluetooth Low Energy beacons directly from Android 5.0 or later, on devices that support BLE peripheral mode.
This library is used in the [Beacon Toy app](https://play.google.com/store/apps/details?id=com.uriio) app.

Examples of supported devices:

- Nexus 6P, 6, 5X, 9;
- Asus Zenfone 2;
- HTC One M9;
- Lenovo K3 Note;
- LG G4;
- Moto G3, X Play, Droid Turbo 2;
- OnePlus ONE 2;
- Samsung Galaxy S5, S6, S7, Note 4, Tab S, J5;
- Sony Xperia Z5 Compact;
- Xiaomi Note 2/3;

### Features
- Create beacons and advertise as:
    * Eddystone-URL
    * Eddystone-UID
    * Eddystone EID, with automatic beacon update when EID expires
    * iBeacon
    * Connectable Eddystone-GATT service, that allows:
        * registering a new EID beacon with [Beacon Tools](https://play.google.com/store/apps/details?id=com.google.android.apps.location.beacon.beacontools))
        * setting up an Eddystone-URL remotely with Web Bluetooth, just by using Chrome
- Persistent Service that manages the virtual beacons and their states
- Beacons are saved to device storage for later reuse
- [Ephemeral URL](https://github.com/uriio/ephemeral-api) API:
    * register long URLs which can be later changed
    * advertise ephemeral (or static) short URLs that redirect

*CAREFUL* - the service will restore active beacons when it (re)starts, so be sure that you either stop or delete a beacon after you no longer need it, If your app crashes, the service may restart and bring the beacon back, so make sure you check what beacons are enabled and stop the ones that you no longer need. Besides freeing resources, every device has a maximum number of concurrent BLE broadcasters (around 4, more or less?), which means when that number is reached, new beacons will fail to start.

### Setup
1. Clone the repo and install the library:

    ```
    > git clone https://github.com/uriio/beacons-android
    > cd beacons-android
    > gradlew install
    ```

For a painless process, make sure your Android SDK and environment are correctly set-up.

2. Add this to your app module's **build.gradle**:

    ```
    dependencies {
    	...
    	compile 'com.uriio:beacons-android:1.3.3'
    }
    ```

3. Initialize the library in the `onCreate()` of your Application, or Activity, or Service:

    ```
    Beacons.initialize(this);  // if you don't need Ephemeral URL support
    
    // OR...
    
    Beacons.initialize(this, "<YOUR_API_KEY_HERE>");
    ```

## Creating beacons

### Eddystone-URL, Eddystone-UID, iBeacon
All constructors support extra arguments, to set their initial Advertise mode, TX power, and name.
Different beacon types will have extra optional arguments.

```
Beacon myUrlBeacon = new EddystoneURL(url);
Beacon myUidBeacon = new EddystoneUID(namespaceInstance);
Beacon myiBeacon = new iBeacon(uuid, major, minor);

Beacons.add(myUrlBeacon);  // saves the beacon and starts it ASAP!
```

After adding a beacon, it will begin to advertise immediately if Bluetooth is on (or when it gets enabled).
Because starting up a beacon is an Android async operation, if there's an error, a broadcast is sent by the service. See the `BleService` class to see the action names of the Intent that you would need to register a receiver for.

### Eddystone EID

An EID beacon first needs to be registered. You can fake a registration and use that to provision an EID beacon.
Realistically, you'll use the built-in Eddystone-GATT feature (see below) and use an external tool (like Beacon Tools) that will register a
new EID beacon with all the ugly details.
```
fakeRegistration = EIDUtils.register(new LocalEIDResolver(), mTemporaryKeyPair.getPublicKey(),
        mTemporaryKeyPair.getPrivateKey(), rotationExponent);

// using the registration result we can now add the EID beacon to the registry and start it:
Beacons.add(new EddystoneEID(registrationResult.getIdentityKey(), rotationExponent,
        registrationResult.getTimeOffset()));
```

### Eddystone-GATT

Eddystone-GATT will run a GATT server and can configure a Eddystone URL/UID/EID beacon.
You receive the final configured beacon in a callback after the owner disconnects.
Every beacon will store its own Lock Key, allowing re-configuration in future versions (since e.g. the Proximity API also keeps the Unlock Key, we must
keep a copy of it too, to allow GATT-based beacon unlocking).

```
// create and start a temporary beacon used to advertise conneectable mode
mPivotBeacon = new EddystoneURL("http://cf.physical-web.org");
Beacons.add(mPivotBeacon);

mGattServer = new EddystoneGattServer(mPivotBeacon, new EddystoneGattServer.Listener() {
    @Override
    public void onGattFinished(EddystoneBase configuredBeacon) {
        if (null != configuredBeacon) {
            Beacons.add(configuredBeacon);
        }
		
		// remove temporary beacon used for GATT connection
		Beacons.delete(mPivotBeacon.getId());
    }
}, new Loggable() {
    @Override
    public void log(String tag, final String message) {
        // log however you want (note: not always called from main thread)
    }
});

// start with an empty UID advertiser with default settings.
EddystoneUID currentBeacon = new EddystoneUID(new byte[16]);

hexUnlockKey = Util.binToHex(currentBeacon.getLockKey(), 0, 16, ' ');

mGattServer.start(mConfigActivity, currentBeacon);

// ... when you are done
mGattServer.close();
```

## Ephemeral URLs

## Registering a redirected long URL

An ephemeral URL broadcasts an Eddystone-URL beacon, but it can dynamically change the advertised URL (and even the target URL).

This feature requires that you initialized the library with an API key, which you can get by visiting the link below.

[Read more about the UriIO API and why it is secure and anti-spoofable.](https://uriio.com)

Example for registering a new URL and creating an Ephemeral URL beacon:

```
// the raw key to use for crypto key-exchange; null = use a default strong java crypto RNG
byte[] temporaryPublicKey = null; // lets the library create a new secure key-pair

String url = 'https://github.com/uriio/beacons-android';

// timeToLive is in seconds; use 0 for an initially non-ephemeral URL
// if non-zero, the UriIO server will invalidate every beacon-advertised URL after it expires using a 404
// ofcourse, you can change the TTL at any time after an URL is registered
Beacons.uriio().registerUrl(url, temporaryPublicKey, new Beacons.OnResultListener<Url>() {
    @Override
    public void onResult(Url result, Throwable error) {
        if (null != result) {
            // yey, URL registered! We can now start a Ephemeral beacon
            Beacons.add(new EphemeralURL(result.getId(), result.getToken(),
                    result.getUrl(), timeToLive));
        }
        else {
            handleError(error);  // registration failed for whatever reason
        }
    }
});
```

The API will issue periodically new short URLs for broadcasting as an Eddystone URL beacon, according to the
timeToLive property. If the TTL is zero, the beacon is not periodically updated with a new URL.

### Updating the target URL

To update the target URL (with or without the need to change the beacon's other properties), use:
```
// item is an existing EphemeralURL
Beacons.uriio().updateUrl(item.getUrlId(), item.getUrlToken(), url, new Beacons.OnResultListener<Url>() {
    @Override
    public void onResult(Url result, Throwable error) {
        if (null != result) {
            // URL target was updated. Save the new value to the local store.
             // note: this is just a local state - the server will redirect to the new URL anyway
            item.edit().setLongUrl(result.getUrl()).apply()
        }
        else {
            showError(error);
        }
    }
});
```

## Editing beacons

### Change properties

For all beacons, you can update a beacon's TX power, broadcast frequency, name.
Some beacons properties are immutable (example: EID identity key or clock offset).
The general pattern to update one or more properties:

```
// note: the chained calls return an Editor, which is not always the actual subclass type.
// to fix this, either use a local variable for edit() return type, or call first the set
// methods defined by highest subclass, and then from super classes.
beacon.edit()
    .set*(value)
    .set*(value)
    .apply();
```

Only if needed (e.g. new TX or frequency), the beacon will restart. Saving is done automatically for you.


### Deleting a beacon

```
Beacons.delete(id)
```

### Changing a beacon's state
A beacon can be in one of three states: Active, Paused, or Stopped. Use ```Beacons.setState()``` to change a beacon's state.

## Listing the beacons
All added beacons are saved in a SQLite database local to your app's storage.

Retrieve the list of active and paused beacons by calling `Beacons.getActive()`

Stopped beacons are saved to storage. To iterate over them, use `Beacons.getStopped()` to get a `Cursor`.

While iterating over the cursor you can call `Storage.itemFromCursor()` to get actual items. 
