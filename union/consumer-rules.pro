# The wrapper bridge is called reflectively by nothing, but wrappers (Flutter / React Native) call it
# from their own modules: keep it out of R8's reach.
-keep class dev.useunion.Union$Wrapper { *; }
-keep class dev.useunion.ManagedFrame { *; }
