ifeq ($(TARGET_FWK_SUPPORTS_FULL_VALUEADDS),true)
ifneq ($(TARGET_HAS_LOW_RAM),true)
PRODUCT_PACKAGES += QtiWifiService
PRODUCT_PACKAGES += android.hardware.wifi.supplicant-V1.0-java
PRODUCT_PACKAGES += android.hardware.wifi.supplicant-V1.1-java
PRODUCT_PACKAGES += android.hardware.wifi.supplicant-V1.2-java
PRODUCT_PACKAGES += android.hardware.wifi.supplicant-V1.3-java
PRODUCT_PACKAGES += android.hidl.base-V1.0-java
PRODUCT_PACKAGES += android.hidl.manager-V1.0-java
PRODUCT_PACKAGES += qti_supplicant_interface.xml
endif #TARGET_HAS_LOW_RAM
endif # TARGET_FWK_SUPPORTS_FULL_VALUEADDS
