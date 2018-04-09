# Curve25519 loads providers by class name
-keep class * implements org.whispersystems.curve25519.Curve25519Provider

# App notification providers get instantiated via reflection by name
-keep class * extends com.uriio.beacons.NotificationProvider {
    <init>(android.content.Context);
}

# Custom beacon kinds are loaded via reflection
-keep class * implements com.uriio.beacons.Storage$Persistable
