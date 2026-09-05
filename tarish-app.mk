# Tarish app — inherited by the device makefile via scripts/gos-tarish.sh in the
# grapheneos repo. Kept separate from the daemon's tarish.mk so a build can take the
# daemon without the app, which is the arrangement used while the app was empty.
PRODUCT_PACKAGES += TarishApp
