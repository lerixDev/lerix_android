# Lerix Release Control uses Google Play in-app updates only when the app ships
# com.google.android.play:app-update (checked at runtime). Without it, R8 must
# not fail on the missing classes.
-dontwarn com.google.android.play.core.**
