## Android BLE beacon advertising library

This library is used by the [Beacon Toy](https://play.google.com/store/apps/details?id=com.uriio) app and is also the main dependency for [Ephemeral URL beacons](https://github.com/uriio/uriio-android)

- [Description](#description)
- [Features](#features)
- [Setup](#setup)
- [Create a beacon](#creating-beacons)
   * [Eddystone-URL / Eddystone-UID / iBeacon](#eddystone-url-eddystone-uid-ibeacon)
   * [Eddystone-EID](#eddystone-eid)
   * [Eddystone-GATT service](#eddystone-gatt)
- [Start, pause, or stop a beacon](#changing-beacon-state)
- [Save a beacon](#saving-beacons)
- [Edit a beacon](#editing-beacons)
- [Delete a beacon](#deleting-a-beacon)
- [List active or stopped beacons](#listing-the-beacons)
- [Listen for events](#listening-for-events)
- [Notification actions](#notification-actions)
    
#### What this library is:

A way for your app to broadcast as a Bluetooth beacon, as explained on this page.

#### What this library **isn't**:

This is not a *beacon scanning* library. Please use either the Nearby API, or (for advanced use-cases) [OneBeacon](https://github.com/Codefy/onebeacon-android) for that.

### Description

Broadcast Bluetooth Low Energy beacons directly from Android 5.0 or later, on devices that support BLE peripheral mode.

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

*CAREFUL* - the service will restore active beacons when it (re)starts, so be sure that you either stop or delete a beacon after you no longer need it, If your app crashes, the service may restart and bring the beacon back, so make sure you check what beacons are enabled and stop the ones that you no longer need. Besides freeing resources, every device has a maximum number of concurrent BLE broadcasters (4 on Nexus 6; 8 on Galaxy S7, etc.). When this number is reached, new beacons will fail to start.

### Setup
1. Add the library to your app module's **build.gradle**.

   ```groovy
   dependencies {
      ...
      compile 'com.uriio:beacons-android:1.4.2'
   }
   ```

2. Initialize the library. Usually you would do this when `onCreate()` is called in either your Application, Activity, or Service.

   ```java
   Beacons.initialize(this);
   ```

## Creating beacons

You can create and start new beacons with one-liners.

### Eddystone-URL, Eddystone-UID, iBeacon

```java
// starts an Eddystone-URL beacon ASAP
new EddystoneURL("https://github.com").start();

// a custom Beacon
new iBeacon(uuid, major, minor).start();

// a more sophisticated beacon
new EddystoneUID(myUID, AdvertiseSettings.ADVERTISE_MODE_BALANCED, AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
   .start();
```

After adding a beacon, it will begin to advertise immediately if Bluetooth is on (or when it gets enabled).
Because starting up a beacon is an Android async operation, if there's an error, a broadcast is sent by the service.
See the [listening for events](#listening-for-events) section for how to handle this.

All beacon constructors support extra arguments, to set their initial properties like Advertise mode, TX power, lock key, or name.

```java
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

```java
fakeRegistration = EIDUtils.register(new LocalEIDResolver(), mTemporaryKeyPair.getPublicKey(),
      mTemporaryKeyPair.getPrivateKey(), rotationExponent);

// using the registration result we can now start an EID beacon
new EddystoneEID(registrationResult.getIdentityKey(), rotationExponent,
      registrationResult.getTimeOffset()).start();
```

### Eddystone-GATT

An actual Eddystone-GATT configuration service can run on the local device, allowing a remote user to configure a new or existing Eddystone URL/UID/EID beacon.

The final configured beacon may be different than the initially configured one, because its type may change (e.g. it was an Eddystone-URL and it ends up an Eddystone-UID, etc.)

You receive the configured beacon in a callback after the owner disconnects. The beacon will already be enabled for advertising.
If you provide an initial configurable beacon, the final beacon will also be automatically saved, if the original beacon was saved.
If the final beacon is of a different type, the original beacon will be deleted, and its Lock Key, name, and other basic properties will be copied to the new beacon.


```java
mGattServer = new EddystoneGattServer(new EddystoneGattServer.Listener() {
   @Override
   public void onGattFinished(EddystoneBase configuredBeacon) {
      if (null != configuredBeacon) {
         // take action - configured beacon is started at this point
         
         // the final beacon's saved state depends on the provided configurable beacon saved state
         // if you provided a non-saved beacon (or none at all), save here if desired
         configuredBeacon.save(true);
      }
      
      // mark object as disposable
      mGattServer = null;   // close() not needed here
   }
});
```

You can then start the GATT service, passing in an optional beacon as the configured beacon.

The beacon will become connectable while being configured, so most probably it will no longer advertise during this time.

```java
// for the initial configured beacon, use an Eddystone-URL that advertises its own Web Bluetooth config URL
boolean success = mGattServer.start();

boolean success = mGattServer.start("https://some.custom.url");

// use a blank Eddystone-UID beacon as the configured beacon
boolean success = mGattServer.start(new EddystoneUID());

// make an existing beacon connectable and configurable. Note that this original
// beacon might end up DELETED if the final configured beacon is of a separate type.
boolean success = mGattServer.start(myExistingBeacon);
```

Every Eddystone beacon has its own Lock Key. To allow future re-configuration, and since the Proximity API also has a field for an Unlock Key, we can't just create a new Unlock Key each time a beacon is configured via GATT.

```java
if (success) {
   // you should present the Unlock Key somehow to the user, since it's needed to connect to the beacon
   String hexUnlockKey = Util.binToHex(mGattServer.getBeacon().getLockKey(), ' ');
}
```

Don't forget to close the GATT service when it's no longer needed ("Cancel" button, activity/fragment closes, etc.)

```java
mGattServer.close();  // ends GATT as if the owner finished config
```

You can attach a simple logging callback to the GATT instance, to display relevant events:

```java
// call this before start() to log start-up errors
mGattServer.setLogger(new Loggable() {
   @Override
   public void log(String tag, final String message) {
      // log however you want (note: this method is not always invoked from the original thread)
      if(VERBOSE) Log.d(tag, message);
   }
});
```

## Changing beacon state

A beacon can be in one of three states: Enabled, Paused, or Stopped.

```java
beacon.start();    // active beacon, it runs when possible
beacon.pause();    // sets a beacon to paused state, e.g. active but not running
beacon.stop();     // stops advertising and removes a beacon from active list
```

## Saving beacons

If you want a beacon to survive between service or app restarts, you should save it to persistent storage.
The default `save()` method will also enable the beacon to advertise.

```java
beacon.save();  // equivalent to: beacon.save(false); beacon.start();

beacon.save(false);  // saves the beacon but does not enable it
```

## Editing beacons

For all beacons, you can update a beacon's TX power, broadcast frequency, name.
Some beacons properties are immutable (example: EID identity key or clock offset).
The general pattern to update one or more properties:

```java
// note: the chained calls return an Editor, which doesn't always auto-cast to the actual subclass.
// to fix this, either use a local variable for edit() return type, or call first the set
// methods defined by the child subclass, and then from its parents.
beacon.edit()
   .setName("My awesome beacon!")
   .setAdvertiseMode(value)
   .apply();
```

Only if really needed (e.g. a new TX power, advertising mode, or beacon payload changes), the beacon will restart. Saving is automatic.

## Deleting a beacon

Use this to permanently remove a beacon from the database.

```java
beacon.delete();
```

## Listing the beacons

If **saved***, beacons are stored in a SQLite database, local to your app's storage.

`Beacons.getActive()` will return the list of *Enabled* and *Paused* beacons. Use `beacon.getActiveState()` to determine if a beacon is enabled or paused.

If you saved a beacon, it can later be retrieved by its saved ID using `Beacons.getSaved()`

To iterate over **all** stopped beacons which are **saved**, use `Beacons.getStopped()` to get a `Cursor`.
You cannot recover back a stopped unsaved beacon through the API, since no references to them are kept.

While iterating over the cursor you can call `Storage.itemFromCursor()` to deserialize the current cursor row into a specific beacon instance. 

## Listening for events

Because beacon lifecycle depends on a lot of factors (Bluetooth state and drivers mainly), they can start and stop at any time.

Your front-end app might not even have a running Activity when a beacon restarts due to a Service or Bluetooth restart, for instance.

To listen for events regarding beacons you have to register a broadcast receiver with a `BleService.ACTION_BEACONS` action filter.

It's your decision if you would like to use a global receiver that will be called when your Activity is stopped, or
use dynamic broadcast receivers inside your code. Or both.

For dynamic receivers it's recommended to register using the `LocalBroadcastManager` from the *appcompat-v7* library, so
the receiver can't be called from other applications. Likewise, global receivers declared in your manifest should be marked with `android:exported="false"`
(which is the default value unless you also declared an intent filter for it).

```java
    @Override
    public void onReceive(Context context, Intent intent) {
        if (BleService.ACTION_BEACONS.equals(intent.getAction())) {
            // some events also contain beacon IDs, or error message / code
            switch (intent.getIntExtra(BleService.EXTRA_BEACON_EVENT, 0)) {
                case BleService.EVENT_ADVERTISER_ADDED:   // new beacon added
                    break;
                case BleService.EVENT_ADVERTISER_STARTED: // BLE transmit on
                    break;
                case BleService.EVENT_ADVERTISER_STOPPED: // BLE transmit off
                    break;
                case BleService.EVENT_ADVERTISER_FAILED:  // error
                    break;
                case BleService.EVENT_ADVERTISE_UNSUPPORTED:
                    break;
                case BleService.EVENT_SHORTURL_FAILED:    // reserved for Ephemeral URLs
                    break;
            }
        }
    }
```

## Notification actions

When there is at least one beacon running, a persistent notification will be created, to allow the service to run as a *foreground service*
(less likely to be killed), and also as a heads-up to the user that their device is an active beacon transmitter.

To provide a hook to the action to be taken when the notification is tapped, do the following:

1. Create (or reuse one) a BroadcastReceiver and add it to your *AndroidManifest.xml*
2. Update *AndroidManifest.xml* with a `meta-data` that points to this receiver, like below:

    ```xml
       <!-- Replace with your receiver. Make sure it remains non-exported -->
       <receiver android:name=".MyReceiver" android:exported="false"/>
       
       <!-- REPLACE <my-package-name> and MyReceiver with actual (sub)package and class name -->
       <meta-data android:name="com.uriio.receiver" android:value="<my-package-name>.MyReceiver" />
    ```
3. Respond to the user tapping the notification content, in your receiver:

    ```java
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BleService.ACTION_NOTIFICATION_CONTENT.equals(intent.getAction())) {
                // this example starts your own Activity
                context.startActivity(new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_BEACONS_NOTIFICATION, true));
            }
        }
    ```

## Building the library

Clone the repo and build the library:

```bash
> git clone https://github.com/uriio/beacons-android
> cd beacons-android
> gradlew build
```

For a painless process, make sure your Android SDK and environment are correctly set-up.