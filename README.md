## Android BLE beacon advertising library

### Description

Android lib for broadcasting BLE beacons right from Android itself. Consists of:

- A restartable service that manages the virtual Bluetooth beacons and their state
- Beacon and data models for Eddystone (URL/UID/EID) and iBeacon types
- A persistence layer (using SQLite) for saving the beacon properties and states.
- [Ephemeral URL](https://github.com/uriio/ephemeral-api) integration:
  * using your own project's API key ([get your API key here](https://api.uriio.com/projects))
  * URL registration and private key creation
  * Ephemeral URL issuing, rescheduling for new ones before they expire by using a RTC system alarm
- A basic EID interface and a local EID resolver, used for making up EID beacons according to the spec (but currently created beacons cannot be resolved externally)

This library is used in the [Beacon Toy app](https://play.google.com/store/apps/details?id=com.uriio)

Android 5.0 or newer is required for creating BLE beacons.

*CAREFUL* - the service will restore active beacons when it (re)starts, so be sure that you either stop or delete a beacon after you no longer need it, If your app crashes, the service may restart and bring the beacon back, so make sure you check what beacons are enabled and stop the ones that you no longer need. Besides freeing resources, every device has a maximum number of concurrent BLE broadcasters (around 4, more or less?), which means when that number is reached, new beacons will fail to start.

### Usage (Android Studio / Gradle)
* Import the uriio-lib folder as a module in your app's main project. In settings.gradle:
```
include ':my-app', ':uriio-lib'
project (":uriio-lib").projectDir = new File("../uriio/beacons-android/uriio-lib")
```
* Add dependency to the library in your app's build.gradle file:

```
compile project(':uriio-lib')
```

* In your Application's onCreate() (or Activity or Service), initialize the API:

```
Uriio.initialize(this, "<YOUR_API_KEY_HERE>");
```

### Ephemeral URL support
To register a new URL, and create a virtual beacon for it, use the ```addBeacon``` method. Pass in the your long URL, the desired TTL for ephemeral URL interval, the beacon's transmit mode and power, and the callback that will give you back the created beacon (from which you can get its token, private key, etc):

```
Uriio.addBeacon("https://example.com/some-long-path?someArgs", 300,
        AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
        AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW,
        new Uriio.OnResultListener<UriioItem>() {
            @Override
            public void onResult(UriioItem result, Throwable error) {
                // there's either a result or an error while registering
            }
        });
```

That's it! If Bluetooth is on, the beacon should also be running. If it's not on, it will start running as soon as it can. If there's an error starting it, you'll have to create a broadcast receiver, since starting a beacon is an async process that can finish well after the beacon was returned to you by the callback. See the ```UriioService``` class for details.

Use the ```deleteBeacon``` or ```enableBeacon``` APIs to remove a beacon or change a beacon's state.

Retrieve the list of your beacons by calling ```getBeacons```

### Other beacon types
For creating other types of beacons (normal Eddystones, iBeacon), you'd have to bind to the ```UriioService``` from your own Activity or Service, and use the create* methods to add NEW beacons (remember, beacons are persisted across app and service restarts).
