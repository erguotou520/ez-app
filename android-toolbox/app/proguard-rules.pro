# Paho loads its logger and network modules by class name / ServiceLoader.
-keep class org.eclipse.paho.client.mqttv3.logging.** { *; }
-keep class * implements org.eclipse.paho.client.mqttv3.spi.NetworkModuleFactory { *; }
