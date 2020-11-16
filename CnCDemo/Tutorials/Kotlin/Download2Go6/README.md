Tutorial - Example6: Client Ads
==================================================
## Summary
This sample demonstrates how to configure the SDK to download client side injected adverts from a VAST/VMAP source. Configuring the SDK for adverts requires the following steps:
1. Add a meta data declaration to the android manifest to enable advertising functions.
2. Add a background processing manager and register it in the android manifest.
3. Create a client ads provider and register this in the background processing manager. Configure the provider to provide the url where the SDK can retrieve the ad definitions document for an asset.
4. Modify your player to check for the ads definition document generated by the SDK, and load it into the player. In this case we use the default exoplayer components.
</br>
</br>