# The wrapper bridge is called reflectively by nothing, but wrappers (Flutter / React Native) call it
# from their own modules: keep it out of R8's reach.
-keep class com.tapll.Tapll$Wrapper { *; }
-keep class com.tapll.ManagedFrame { *; }
