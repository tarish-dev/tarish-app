# Barq app — inherited by the device makefile via scripts/gos-barq.sh in the
# grapheneos repo. Kept separate from the daemon's barq.mk so a build can take the
# daemon without the app, which is the arrangement used while the app was empty.
PRODUCT_PACKAGES += BarqApp
