# lerix-core finds push by class lookup (Class.forName + the Kotlin object's
# INSTANCE field), so R8 must keep both.
-keep class com.lerix.sdk.notifications.LerixNotifications {
    public static final com.lerix.sdk.notifications.LerixNotifications INSTANCE;
}
