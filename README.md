## Android BLE beacon advertising library

- [Description](#description)
- [Features](#features)
- [Setup](#setup)
- [Creating beacons](#creating-beacons)
   * [Eddystone-URL / Eddystone-UID / iBeacon](#eddystone-url-eddystone-uid-ibeacon)
   * [Eddystone-EID](#eddystone-eid)
   * [Eddystone-GATT service](#eddystone-gatt)
- [Editing beacons](#editing-beacons)
   * [Change properties](#change-properties)
   * [Deleting a beacon](#deleting-a-beacon)
   * [Listing the beacons](#listing-the-beacons)
- [Ephemeral URLs](#ephemeral-urls)
   * [Register a redirected URL](#registering-a-redirected-long-url)
   * [Update destination URL](#updating-the-target-url)
    
#### What this library is:

A way for your app to broadcast as a Bluetooth beacon, as explained on this page.

#### What this library **isn't**:

This is not a *beacon scanning* library. Please use either the Nearby API, or (for advanced use-cases) [OneBeacon](https://github.com/Codefy/onebeacon-android) for that.

### Description

Broadcast Bluetooth Low Energy beacons directly from Android 5.0 or later, on devices that support BLE peripheral mode.
This library is used by the [Beacon Toy](https://play.google.com/store/apps/details?id=com.uriio) app.

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
      * registering a new EID beacon with [Beacon Tools](https://play.google.com/store/apps/details?id=com.google.android.apps.location.beacon.beacontools)
      * setting up an Eddystone-URL remotely with Web Bluetooth, just by using Chrome
- Persistent Service that manages the virtual beacons and their states
- Beacons are saved to device storage for later reuse
- [Ephemeral URL](https://github.com/uriio/ephemeral-api) API:
   * register long URLs which can be later changed
   * advertise ephemeral (or static) short URLs that redirect

*CAREFUL* - the service will restore active beacons when it (re)starts, so be sure that you either stop or delete a beacon after you no longer need it, If your app crashes, the service may restart and bring the beacon back, so make sure you check what beacons are enabled and stop the ones that you no longer need. Besides freeing resources, every device has a maximum number of concurrent BLE broadcasters (4 on Nexus 6; 8 on Galaxy S7, etc.). When this number is reached, new beacons will fail to start.

### Setup
1. Add the library to your app module's **build.gradle**:

   ```
   dependencies {
      ...
      compile 'com.uriio:beacons-android:1.3.4'
   }
   ```

2. Initialize the library in the `onCreate()` of your Application, or Activity, or Service:

   ```
   Beacons.initialize(this);  // if you don't need Ephemeral URL support
   
   // OR...
   
   Beacons.initialize(this, "<YOUR_API_KEY_HERE>");
   ```

## Creating beacons

### Eddystone-URL, Eddystone-UID, iBeacon

You can create, start, and even modify new beacons with one-liners:

```
// saves a new  Eddystone-URL beacon and starts it ASAP!
Beacons.add("https://github.com");

// pass in a custom Beacon
Beacons.add(new iBeacon(uuid, major, minor));

// provide a more sophisticated beacon
Beacons.add(new EddystoneUID(myUID, AdvertiseSettings.ADVERTISE_MODE_BALANCED, AdvertiseSettings.ADVERTISE_TX_POWER_LOW));

// add a beacon and change its name
Beacons.add("https://github.com").edit().setName("an awesome beacon").apply();
```

After adding a beacon, it will begin to advertise immediately if Bluetooth is on (or when it gets enabled).
Because starting up a beacon is an Android async operation, if there's an error, a broadcast is sent by the service. See the `BleService` class to see the action names of the Intent that you would need to register a receiver for.

All beacon constructors support extra arguments, to set their initial properties like Advertise mode, TX power, lock key, or name.

```
Beacon myUrlBeacon = new EddystoneURL(url, ...);
Beacon myUidBeacon = new EddystoneUID(namespaceInstance), ...;
Beacon myiBeacon = new iBeacon(uuid, major, minor, ...);
```

### Eddystone EID

The library supports full production-ready EID beacons. The beacon's advertised EID will automatically update
when needed, using scheduled Android system Alarms, so with zero battery impact.

An EID beacon first needs to be registered. For testing only, you can fake a registration and use that to provision an EID beacon.
Much better, just use the built-in Eddystone-GATT service (see below) and use an external tool (like Beacon Tools) to register a
new EID beacon. That will take care of all the ugly details.

```
fakeRegistration = EIDUtils.register(new LocalEIDResolver(), mTemporaryKeyPair.getPublicKey(),
      mTemporaryKeyPair.getPrivateKey(), rotationExponent);

// using the registration result we can now add the EID beacon to the registry and start it:
Beacons.add(new EddystoneEID(registrationResult.getIdentityKey(), rotationExponent,
      registrationResult.getTimeOffset()));
```

### Eddystone-GATT

An actual Eddystone-GATT configuration service can run on the local device, allowing a remote user to configure a new or existing Eddystone URL/UID/EID beacon.

The final configured beacon's type may be different than the one provided to GATT, because its type may change (e.g. it was an Eddystone-URL and it ends up an Eddystone-UID, etc.)

You receive the configured beacon in a callback after the owner disconnects. The beacon will already be saved and running.

```
mGattServer = new EddystoneGattServer(new EddystoneGattServer.Listener() {
   @Override
   public void onGattFinished(EddystoneBase configuredBeacon) {
      if (null != configuredBeacon) {
         // take action. The (new) beacon is already saved
      }
      mGattServer = null;   // close() not needed here
   }
});
```

You can then start the GATT service, passing in an optional beacon as the configured beacon.

The beacon will become connectable while being configured, so most probably it will no longer advertise during this time.

```
// use a new, blank, default Eddystone-UID beacon as the configured beacon
boolean success = mGattServer.start(context)

// use a new Eddystone-URL that advertises its own Web Bluetooth config URL
boolean success = mGattServer.start(context, "http://cf.physical-web.org")

// make an existing beacon connectable and configurable. Note that this original
// beacon might end up DELETED if the final configured beacon is of a separate type.
boolean success = mGattServer.start(context, myExistingBeacon)
```

Every Eddystone beacon has its own Lock Key. To allow future re-configuration, and since the Proximity API also has a field for an Unlock Key, we can't just create a new Unlock Key each time a beacon is configured via GATT.

```
if (success) {
   // you should present the Unlock Key somehow to the user, since it's needed to connect to the beacon
   String hexUnlockKey = Util.binToHex(mGattServer.getBeacon().getLockKey(), ' ');
}
```

Don't forget to close the GATT service when it's no longer needed ("Cancel" button, activity/fragment closes, etc.)

```
mGattServer.close();  // ends GATT as if the owner finished config
```

You can attach a simple logging callback to the GATT instance, to display relevant events:

```
// call this before start() to log start-up errors
mGattServer.setLogger(new Loggable() {
   @Override
   public void log(String tag, final String message) {
      // log however you want (note: this method is not always invoked from the original thread)
      if(VERBOSE) Log.d(tag, message);
   }
});
```

## Editing beacons

### Change properties

For all beacons, you can update a beacon's TX power, broadcast frequency, name.
Some beacons properties are immutable (example: EID identity key or clock offset).
The general pattern to update one or more properties:

```
// note: the chained calls return an Editor, which doesn't always auto-cast to the actual subclass.
// to fix this, either use a local variable for edit() return type, or call first the set
// methods defined by the child subclass, and then from its parents.
beacon.edit()
   .setName(value)
   .setAdvertiseMode(value)
   .apply();
```

Only if needed (e.g. new TX or frequency), the beacon will restart. Saving is done automatically for you.

### Deleting a beacon

```
Beacons.delete(myBeacon);  // also accepts an ID of a persisted beacon
```

### Changing a beacon's state
A beacon can be in one of three states: Active, Paused, or Stopped. Use ```Beacons.setState()``` to change a beacon's state.

## Listing the beacons
All added beacons are saved in a SQLite database local to your app's storage.

Retrieve the list of active and paused beacons by calling `Beacons.getActive()`

Stopped beacons are saved to storage. To iterate over them, use `Beacons.getStopped()` to get a `Cursor`.

While iterating over the cursor you can call `Storage.itemFromCursor()` to get actual items. 

## Ephemeral URLs

An ephemeral URL broadcasts an Eddystone-URL beacon, but it can dynamically change the advertised URL (and even the target URL).

This feature requires that you initialized the library with an API key, which you can get by visiting the link below.

[Read more about the UriIO API and why it is secure and anti-spoofable.](https://uriio.com)

## Registering a redirected long URL

This snippet registers a new "long" URL destination and creates an Ephemeral URL beacon for it:

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
         // since this is a subclass of EddystoneURL you can adjust its other
         // properties like TX power, mode, etc.
      }
      else {
         handleError(error);  // registration failed for whatever reason
      }
   }
});
```

The library will call the specific APIs for issuing periodically new short URLs, and recreate the Eddystone-URL beacon, according to the timeToLive property. If the TTL is zero, the beacon's URL remains the same.

### Updating the target URL

To update the target URL (with or without the need to change other beacon properties), use:

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

## Building the library

Clone the repo and build the library:

```
> git clone https://github.com/uriio/beacons-android
> cd beacons-android
> gradlew build
```

For a painless process, make sure your Android SDK and environment are correctly set-up.
