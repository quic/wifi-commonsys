# wifi-commonsys

This is a new wifi-commonsys project used to have a separate service parallel to wifi service named as QtiWifiService

# QtiWifiService

This service is getting started at boot completion of the device. This service can be used to have oem specific feature development.
Currently this service is being used to collect CSI data from cfrtool via hidl and then pass the data to application(user level).

# QtiWifiSettingsApp(APK)

This is an user level application can be used to interact with service and then can collect information from the service.
In current case we are using this application to collect CSI data and save into files using startCSI and stopCSI buttons added into the application.

To use this APK below are the libs, permission files and the QtiWifiservice shall be added to the device through the build using manifest file.

- [QtiWifiService]
- [android.hardware.wifi.supplicant-V1.0-java]
- [android.hardware.wifi.supplicant-V1.1-java]
- [android.hardware.wifi.supplicant-V1.2-java]
- [android.hardware.wifi.supplicant-V1.3-java]
- [android.hidl.base-V1.0-java]
- [android.hidl.manager-V1.0-java]
- [qti_supplicant_interface.xml(permission file)]
