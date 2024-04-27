package com.capacitorjs.plugins.googlemaps

import android.annotation.SuppressLint
import android.graphics.*
import android.location.Location
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.getcapacitor.Bridge
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.google.android.gms.maps.*
import com.google.android.gms.maps.GoogleMap.*
import com.google.android.gms.maps.model.*
import com.google.maps.android.clustering.Cluster
import com.google.maps.android.clustering.ClusterManager
import com.google.maps.android.data.Feature
import com.google.maps.android.data.geojson.GeoJsonFeature
import com.google.maps.android.data.geojson.GeoJsonGeometryCollection
import com.google.maps.android.data.geojson.GeoJsonLayer
import com.google.maps.android.data.geojson.GeoJsonLineString
import com.google.maps.android.data.geojson.GeoJsonMultiLineString
import com.google.maps.android.data.geojson.GeoJsonMultiPoint
import com.google.maps.android.data.geojson.GeoJsonMultiPolygon
import com.google.maps.android.data.geojson.GeoJsonPoint
import com.google.maps.android.data.geojson.GeoJsonPolygon
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL


class CapacitorGoogleMap(
        val id: String,
        val config: GoogleMapConfig,
        val delegate: CapacitorGoogleMapsPlugin
) :
        OnCameraIdleListener,
        OnCameraMoveStartedListener,
        OnCameraMoveListener,
        OnMyLocationButtonClickListener,
        OnMyLocationClickListener,
        OnMapReadyCallback,
        OnMapClickListener,
        OnMarkerClickListener,
        OnMarkerDragListener,
        OnInfoWindowClickListener,
        OnCircleClickListener,
        OnPolylineClickListener,
        OnPolygonClickListener {
    private var mapView: MapView
    private var googleMap: GoogleMap? = null
    private val markers = HashMap<String, CapacitorGoogleMapMarker>()
    private val polygons = HashMap<String, CapacitorGoogleMapsPolygon>()
    private val circles = HashMap<String, CapacitorGoogleMapsCircle>()
    private val polylines = HashMap<String, CapacitorGoogleMapPolyline>()
    private val featureLayers = HashMap<String, CapacitorGoogleMapsFeatureLayer>()
    private val markerIcons = HashMap<String, Bitmap>()
    private var clusterManager: ClusterManager<CapacitorGoogleMapMarker>? = null

    private val isReadyChannel = Channel<Boolean>()
    private var debounceJob: Job? = null

    init {
        val bridge = delegate.bridge

        mapView = MapView(bridge.context, config.googleMapOptions)
        initMap()
        setListeners()
    }

    private fun initMap() {
        runBlocking {
            val job =
                    CoroutineScope(Dispatchers.Main).launch {
                        mapView.onCreate(null)
                        mapView.onStart()
                        mapView.getMapAsync(this@CapacitorGoogleMap)
                        mapView.setWillNotDraw(false)
                        isReadyChannel.receive()

                        render()
                    }

            job.join()
        }
    }

    private fun render() {
        runBlocking {
            CoroutineScope(Dispatchers.Main).launch {
                val bridge = delegate.bridge
                val mapViewParent = FrameLayout(bridge.context)
                mapViewParent.minimumHeight = bridge.webView.height
                mapViewParent.minimumWidth = bridge.webView.width

                val layoutParams =
                        FrameLayout.LayoutParams(
                                getScaledPixels(bridge, config.width),
                                getScaledPixels(bridge, config.height),
                        )
                layoutParams.leftMargin = getScaledPixels(bridge, config.x)
                layoutParams.topMargin = getScaledPixels(bridge, config.y)

                mapViewParent.tag = id

                mapView.layoutParams = layoutParams
                mapViewParent.addView(mapView)

                ((bridge.webView.parent) as ViewGroup).addView(mapViewParent)

                bridge.webView.bringToFront()
                bridge.webView.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    fun updateRender(updatedBounds: RectF) {
        this.config.x = updatedBounds.left.toInt()
        this.config.y = updatedBounds.top.toInt()
        this.config.width = updatedBounds.width().toInt()
        this.config.height = updatedBounds.height().toInt()

        runBlocking {
            CoroutineScope(Dispatchers.Main).launch {
                val bridge = delegate.bridge
                val mapRect = getScaledRect(bridge, updatedBounds)
                val mapView = this@CapacitorGoogleMap.mapView;
                mapView.x = mapRect.left
                mapView.y = mapRect.top
                if (mapView.layoutParams.width != config.width || mapView.layoutParams.height != config.height) {
                    mapView.layoutParams.width = getScaledPixels(bridge, config.width)
                    mapView.layoutParams.height = getScaledPixels(bridge, config.height)
                    mapView.requestLayout()
                }
            }
        }
    }

    fun dispatchTouchEvent(event: MotionEvent) {
        CoroutineScope(Dispatchers.Main).launch {
            val offsetViewBounds = getMapBounds()

            val relativeTop = offsetViewBounds.top
            val relativeLeft = offsetViewBounds.left

            event.setLocation(event.x - relativeLeft, event.y - relativeTop)
            mapView.dispatchTouchEvent(event)
        }
    }

        fun applyConfig(configObject: JSObject, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            CoroutineScope(Dispatchers.Main).launch {
                if (configObject.has("gestureHandling")) {
                    googleMap?.uiSettings?.setAllGesturesEnabled(configObject.getString("gestureHandling") != "none")
                }

                if (configObject.has("isCompassEnabled")) {
                    googleMap?.uiSettings?.isCompassEnabled = configObject.getBool("isCompassEnabled") == true
                }

                if (configObject.has("isIndoorMapsEnabled")) {
                    googleMap?.isIndoorEnabled = configObject.getBool("isIndoorMapsEnabled") == true
                }

                if (configObject.has("isMyLocationButtonEnabled")) {
                    googleMap?.uiSettings?.isMyLocationButtonEnabled = configObject.getBool("isMyLocationButtonEnabled") == true
                }

                if (configObject.has("isMyLocationEnabled")) {
                    @SuppressLint("MissingPermission")
                    googleMap?.isMyLocationEnabled = configObject.getBool("isMyLocationEnabled") == true
                }

                if (configObject.has("isRotateGesturesEnabled")) {
                    googleMap?.uiSettings?.isRotateGesturesEnabled =  configObject.getBool("isRotateGesturesEnabled") == true
                }

                if (configObject.has("isTiltGesturesEnabled")) {
                    googleMap?.uiSettings?.isTiltGesturesEnabled = configObject.getBool("isTiltGesturesEnabled") == true
                }

                if (configObject.has("isToolbarEnabled")) {
                    googleMap?.uiSettings?.isMapToolbarEnabled = configObject.getBool("isToolbarEnabled") == true
                }

                if (configObject.has("isTrafficLayerEnabled")) {
                    googleMap?.isTrafficEnabled = configObject.getBool("isTrafficLayerEnabled") == true
                }

                if (configObject.has("isZoomGesturesEnabled")) {
                    googleMap?.uiSettings?.isZoomGesturesEnabled = configObject.getBool("isZoomGesturesEnabled") == true
                }

                if (configObject.has("mapTypeId")) {
                    setMapType(configObject.getString("mapTypeId"))
                }

                if (configObject.has("maxZoom")) {
                    setMaxZoom(configObject.getDouble("maxZoom").toFloat())
                }

                if (configObject.has("minZoom")) {
                    setMinZoom(configObject.getDouble("minZoom").toFloat())
                }

                if (configObject.has("padding")) {
                    setPadding(configObject.getJSObject("padding"))
                }

                if (configObject.has("restriction")) {
                    setRestriction(configObject.getJSObject("restriction"))
                }

                if (configObject.has("styles")) {
                    googleMap?.setMapStyle(configObject.getString("styles")
                        ?.let { MapStyleOptions(it) })
                }

                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    private fun setMapType(mapTypeId: String?) {
        val mapTypeInt: Int =
            when (mapTypeId) {
                "Normal" -> MAP_TYPE_NORMAL
                "Hybrid" -> MAP_TYPE_HYBRID
                "Satellite" -> MAP_TYPE_SATELLITE
                "Terrain" -> MAP_TYPE_TERRAIN
                "None" -> MAP_TYPE_NONE
                else -> {
                    Log.w(
                        "CapacitorGoogleMaps",
                        "unknown mapView type '$mapTypeId'  Defaulting to normal."
                    )
                    MAP_TYPE_NORMAL
                }
            }

        googleMap?.mapType = mapTypeInt
    }

    private fun setMaxZoom(maxZoom: Float) {
        var minZoom = googleMap?.minZoomLevel
        googleMap?.resetMinMaxZoomPreference()
        if (minZoom != null) {
            googleMap?.setMinZoomPreference(minZoom)
        }
        if (maxZoom != 0F) {
            googleMap?.setMinZoomPreference(maxZoom)
        }
    }

    private fun setMinZoom(minZoom: Float) {
        var maxZoom = googleMap?.maxZoomLevel
        googleMap?.resetMinMaxZoomPreference()
        if (maxZoom != null) {
            googleMap?.setMaxZoomPreference(maxZoom)
        }
        if (minZoom != 0F) {
            googleMap?.setMinZoomPreference(minZoom)
        }
    }

    private fun setPadding(paddingObj: JSObject?) {
        if (paddingObj == null) {
            googleMap?.setPadding(0, 0, 0, 0)
        } else {
            val padding = GoogleMapPadding(paddingObj)
            val left = getScaledPixels(delegate.bridge, padding.left)
            val top = getScaledPixels(delegate.bridge, padding.top)
            val right = getScaledPixels(delegate.bridge, padding.right)
            val bottom = getScaledPixels(delegate.bridge, padding.bottom)
            googleMap?.setPadding(left, top, right, bottom)
        }
    }

    private fun setRestriction(restrictionObj: JSObject?) {
        var latLngBounds = restrictionObj?.getJSObject("latLngBounds")
        var bounds: LatLngBounds? = null

        if (latLngBounds != null) {
            bounds = createLatLngBoundsFromGMSJS(latLngBounds)
        }

        googleMap?.resetMinMaxZoomPreference()
        googleMap?.setLatLngBoundsForCameraTarget(null)

        if (bounds != null) {
            googleMap?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0),
                object : CancelableCallback {
                    override fun onFinish() {
                        val zoom = googleMap?.cameraPosition?.zoom
                        if (zoom != null) {
                            googleMap?.setMinZoomPreference(zoom)
                        }
                        googleMap?.setLatLngBoundsForCameraTarget(bounds)
                    }
                    override fun onCancel() {}
                }
            )
        }
    }

    fun bringToFront() {
        CoroutineScope(Dispatchers.Main).launch {
            val mapViewParent =
                    ((delegate.bridge.webView.parent) as ViewGroup).findViewWithTag<ViewGroup>(
                            this@CapacitorGoogleMap.id
                    )
            mapViewParent.bringToFront()
        }
    }

    fun destroy() {
        runBlocking {
            val job =
                    CoroutineScope(Dispatchers.Main).launch {
                        val bridge = delegate.bridge

                        val viewToRemove: View? =
                                ((bridge.webView.parent) as ViewGroup).findViewWithTag(id)
                        if (null != viewToRemove) {
                            ((bridge.webView.parent) as ViewGroup).removeView(viewToRemove)
                        }
                        mapView.onDestroy()
                        googleMap = null
                        clusterManager = null
                    }

            job.join()
        }
    }

        fun addTileOverlay(tileOverlay: CapacitorGoogleMapsTileOverlay, callback: (result: Result<String>) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()
            var tileOverlayId: String

            val bitmapFunc = CoroutineScope(Dispatchers.IO).async {
                val url = URL(tileOverlay.imageSrc)
                val connection: HttpURLConnection = url.openConnection() as HttpURLConnection

                connection.doInput = true
                connection.connect()

                val input: InputStream = connection.inputStream

                BitmapFactory.decodeStream(input)
            }

            CoroutineScope(Dispatchers.Main).launch {
                /*
                var tileProvider: TileProvider = object : UrlTileProvider(256, 256) {
                    override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? {
                        /* Define the URL pattern for the tile images */
                        val url = "https://avatars.githubusercontent.com/u/103097039?v=4"
                        return if (!checkTileExists(x, y, zoom)) {
                            null
                        } else try {
                            URL(url)
                        } catch (e: MalformedURLException) {
                            throw AssertionError(e)
                        }
                    }
                    /*
                     * Check that the tile server supports the requested x, y and zoom.
                     * Complete this stub according to the tile range you support.
                     * If you support a limited range of tiles at different zoom levels, then you
                     * need to define the supported x, y range at each zoom level.
                     */
                    private fun checkTileExists(x: Int, y: Int, zoom: Int): Boolean {
                        val minZoom = 1
                        val maxZoom = 16
                        return zoom in minZoom..maxZoom
                    }
                }
                Log.d("TileOverlay ^^^ ", "tileProvider")
                val tileOverlay = googleMap?.addTileOverlay(
                    TileOverlayOptions()
                        .tileProvider(tileProvider)
                )
                */

                val bitmap = bitmapFunc.await()

                // Now you can safely use the bitmap
                if (bitmap != null) {
                    val imageDescriptor = BitmapDescriptorFactory.fromBitmap(bitmap)

                    val groundOverlay = googleMap?.addGroundOverlay(
                        GroundOverlayOptions()
                            .image(imageDescriptor)
                            .positionFromBounds(tileOverlay.imageBounds)
                            .transparency(tileOverlay.opacity)
                            .zIndex(tileOverlay.zIndex)
                            .visible(tileOverlay.visible)
                    )

                    tileOverlay.googleMapsTileOverlay = groundOverlay
                    tileOverlayId = groundOverlay!!.id

                    callback(Result.success(tileOverlayId))
                }
            }
        } catch (e: GoogleMapsError) {
            callback(Result.failure(e))
        }
    }

    fun addMarkers(
            newMarkers: List<CapacitorGoogleMapMarker>,
            callback: (ids: Result<List<String>>) -> Unit
    ) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()
            val markerIds: MutableList<String> = mutableListOf()

            CoroutineScope(Dispatchers.Main).launch {
                val markerOptionsList = withContext(Dispatchers.IO) {
                    newMarkers.map { buildMarker(it) }
                }

                markerOptionsList.forEachIndexed { index, markerOptions ->
                    val googleMapMarker = googleMap?.addMarker(markerOptions)
                    val capacitorMarker = newMarkers[index]
                    googleMapMarker?.let { marker ->
                        if (clusterManager != null) {
                            marker.remove()
                        } else {
                            markers[marker.id] = capacitorMarker
                            markerIds.add(marker.id)
                        }
                    }
                }
                clusterManager?.let { manager ->
                    manager.addItems(newMarkers)
                    manager.cluster()
                }

                callback(Result.success(markerIds))
            }
        } catch (e: GoogleMapsError) {
            callback(Result.failure(e))
        }
    }

    fun addMarker(marker: CapacitorGoogleMapMarker, callback: (result: Result<String>) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            var markerId: String

            CoroutineScope(Dispatchers.Main).launch {
                val markerOptions: Deferred<MarkerOptions> =
                        CoroutineScope(Dispatchers.IO).async {
                            this@CapacitorGoogleMap.buildMarker(marker)
                        }
                val googleMapMarker = googleMap?.addMarker(markerOptions.await())

                marker.googleMapMarker = googleMapMarker

                if (clusterManager != null) {
                    googleMapMarker?.remove()
                    clusterManager?.addItem(marker)
                    clusterManager?.cluster()
                }

                markers[googleMapMarker!!.id] = marker

                markerId = googleMapMarker.id

                callback(Result.success(markerId))
            }
        } catch (e: GoogleMapsError) {
            callback(Result.failure(e))
        }
    }

    fun addPolygons(newPolygons: List<CapacitorGoogleMapsPolygon>, callback: (ids: Result<List<String>>) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()
            val shapeIds: MutableList<String> = mutableListOf()

            CoroutineScope(Dispatchers.Main).launch {
                newPolygons.forEach {
                    val polygonOptions: Deferred<PolygonOptions> = CoroutineScope(Dispatchers.IO).async {
                        this@CapacitorGoogleMap.buildPolygon(it)
                    }

                    val googleMapsPolygon = googleMap?.addPolygon(polygonOptions.await())
                    googleMapsPolygon?.tag = it.tag

                    it.googleMapsPolygon = googleMapsPolygon

                    polygons[googleMapsPolygon!!.id] = it
                    shapeIds.add(googleMapsPolygon.id)
                }

                callback(Result.success(shapeIds))
            }
        } catch (e: GoogleMapsError) {
            callback(Result.failure(e))
        }
    }

    fun addCircles(newCircles: List<CapacitorGoogleMapsCircle>,callback: (ids: Result<List<String>>) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()
            val circleIds: MutableList<String> = mutableListOf()

            CoroutineScope(Dispatchers.Main).launch {
                newCircles.forEach {
                    var circleOptions: Deferred<CircleOptions> = CoroutineScope(Dispatchers.IO).async {
                        this@CapacitorGoogleMap.buildCircle(it)
                    }

                    val googleMapsCircle = googleMap?.addCircle(circleOptions.await())
                    googleMapsCircle?.tag = it.tag

                    it.googleMapsCircle = googleMapsCircle

                    circles[googleMapsCircle!!.id] = it
                    circleIds.add(googleMapsCircle.id)
                }

                callback(Result.success(circleIds))
            }
        } catch (e: GoogleMapsError) {
            callback(Result.failure(e))
        }
    }

    fun addPolylines(newLines: List<CapacitorGoogleMapPolyline>, callback: (ids: Result<List<String>>) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()
            val lineIds: MutableList<String> = mutableListOf()

            CoroutineScope(Dispatchers.Main).launch {
                newLines.forEach {
                    val polylineOptions: Deferred<PolylineOptions> = CoroutineScope(Dispatchers.IO).async {
                        this@CapacitorGoogleMap.buildPolyline(it)
                    }
                    val googleMapPolyline = googleMap?.addPolyline(polylineOptions.await())
                    googleMapPolyline?.tag = it.tag
                    
                    it.googleMapsPolyline = googleMapPolyline

                    polylines[googleMapPolyline!!.id] = it
                    lineIds.add(googleMapPolyline.id)
                }

                callback(Result.success(lineIds))
            }
        } catch (e: GoogleMapsError) {
            callback(Result.failure(e))
        }
    }
    
    fun getRawGoogleMapInstance(callback: (type: String, error: GoogleMapsError?) -> Unit) {
        throw GoogleMapsError("Not implemented on Android platform.");
    }

    fun addFeatures(type: String, data: JSONObject, idPropertyName: String?, styles: JSONObject?, callback: (ids: Result<List<String>>) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()
            val featureIds: MutableList<String> = mutableListOf()

            CoroutineScope(Dispatchers.Main).launch {
                if (type == "GeoJSON") {
                    val tempLayer = GeoJsonLayer(null, data)
                    tempLayer.features.forEach {
                        try {
                            val layer = GeoJsonLayer(googleMap, JSONObject())
                            val featureLayer = CapacitorGoogleMapsFeatureLayer(layer, it, idPropertyName, styles)
                            layer.addLayerToMap()
                            if (id != null) {
                                featureIds.add(id)
                                featureLayers[id] = featureLayer
                            }
                            callback(Result.success(featureIds))
                        } catch (e: Exception) {
                            callback(Result.failure(e))
                        }
                    }
                } else {
                    callback(Result.failure(InvalidArgumentsError("addFeatures: not supported for this feature type")))
                }
            }
        } catch (e: GoogleMapsError) {
            callback(Result.failure(e))
        }
    }

    fun getFeatureBounds(featureId: String, callback: (bounds: Result<LatLngBounds?>) -> Unit) {
        try {
            CoroutineScope(Dispatchers.Main).launch {
                val featurelayer = featureLayers[featureId]
                var feature: Feature? = null;
                featurelayer?.layer?.features?.forEach lit@ {
                    if (it.id == featurelayer.id) {
                        feature = it
                        return@lit
                    }
                }
                if (feature != null) {
                    try {
                        (feature as GeoJsonFeature).let {
                            callback(Result.success(it.boundingBoxFromGeometry()))
                        }
                    } catch (e: Exception) {
                        callback(Result.failure(InvalidArgumentsError("getFeatureBounds: not supported for this feature type")))
                    }
                } else {
                    callback(Result.failure(InvalidArgumentsError("Could not find feature for feature id $featureId")))
                }
            }
        } catch(e: Exception) {
            callback(Result.failure(InvalidArgumentsError("Could not find feature layer")))
        }
    }

    fun removeFeature(featureId: String, callback: (error: GoogleMapsError?) -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            val featurelayer = featureLayers[featureId]
            if (featurelayer != null) {
                featurelayer.layer?.removeLayerFromMap()
                featureLayers.remove(featureId)
                callback(null)
            } else {
                callback(InvalidArgumentsError("Could not find feature for feature id $featureId"))
            }
        }
    }

    private fun setClusterManagerRenderer(minClusterSize: Int?) {
        clusterManager?.renderer = CapacitorClusterManagerRenderer(
            delegate.bridge.context,
            googleMap,
            clusterManager,
            minClusterSize
        )
    }

    @SuppressLint("PotentialBehaviorOverride")
    fun enableClustering(minClusterSize: Int?, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            CoroutineScope(Dispatchers.Main).launch {
                if (clusterManager != null) {
                    setClusterManagerRenderer(minClusterSize)
                    callback(null)
                    return@launch
                }

                val bridge = delegate.bridge
                clusterManager = ClusterManager(bridge.context, googleMap)

                setClusterManagerRenderer(minClusterSize)
                setClusterListeners()

                // add existing markers to the cluster
                if (markers.isNotEmpty()) {
                    for ((_, marker) in markers) {
                        marker.googleMapMarker?.remove()
                        // marker.googleMapMarker = null
                    }
                    clusterManager?.addItems(markers.values)
                    clusterManager?.cluster()
                }

                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    @SuppressLint("PotentialBehaviorOverride")
    fun disableClustering(callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            CoroutineScope(Dispatchers.Main).launch {
                clusterManager?.clearItems()
                clusterManager?.cluster()
                clusterManager = null

                googleMap?.setOnMarkerClickListener(this@CapacitorGoogleMap)

                // add existing markers back to the map
                if (markers.isNotEmpty()) {
                    for ((_, marker) in markers) {
                        val markerOptions: Deferred<MarkerOptions> =
                                CoroutineScope(Dispatchers.IO).async {
                                    this@CapacitorGoogleMap.buildMarker(marker)
                                }
                        val googleMapMarker = googleMap?.addMarker(markerOptions.await())
                        marker.googleMapMarker = googleMapMarker
                    }
                }

                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun removePolygons(ids: List<String>, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            CoroutineScope(Dispatchers.Main).launch {
                ids.forEach {
                    val polygon = polygons[it]
                    if (polygon != null) {
                        polygon.googleMapsPolygon?.remove()
                        polygons.remove(it)
                    }
                }

                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun removeMarker(id: String, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            val marker = markers[id]
            marker ?: throw MarkerNotFoundError()

            CoroutineScope(Dispatchers.Main).launch {
                if (clusterManager != null) {
                    clusterManager?.removeItem(marker)
                    clusterManager?.cluster()
                }

                marker.googleMapMarker?.remove()
                markers.remove(id)

                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun removeMarkers(ids: List<String>, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            CoroutineScope(Dispatchers.Main).launch {
                val deletedMarkers: MutableList<CapacitorGoogleMapMarker> = mutableListOf()

                ids.forEach {
                    val marker = markers[it]
                    if (marker != null) {
                        marker.googleMapMarker?.remove()
                        markers.remove(it)

                        deletedMarkers.add(marker)
                    }
                }

                if (clusterManager != null) {
                    clusterManager?.removeItems(deletedMarkers)
                    clusterManager?.cluster()
                }

                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun removeCircles(ids: List<String>, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            CoroutineScope(Dispatchers.Main).launch {
                ids.forEach {
                    val circle = circles[it]
                    if (circle != null) {
                        circle.googleMapsCircle?.remove()
                        markers.remove(it)
                    }
                }

                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun removePolylines(ids: List<String>, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            CoroutineScope(Dispatchers.Main).launch {
                ids.forEach {
                    val polyline = polylines[it]
                    if (polyline != null) {
                        polyline.googleMapsPolyline?.remove()
                        polylines.remove(it)
                    }
                }

                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun setCamera(config: GoogleMapCameraConfig, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()
            CoroutineScope(Dispatchers.Main).launch {
                val currentPosition = googleMap!!.cameraPosition

                var updatedTarget = config.coordinate
                if (updatedTarget == null) {
                    updatedTarget = currentPosition.target
                }

                var zoom = config.zoom
                if (zoom == null) {
                    zoom = currentPosition.zoom.toDouble()
                }

                var bearing = config.bearing
                if (bearing == null) {
                    bearing = currentPosition.bearing.toDouble()
                }

                var angle = config.angle
                if (angle == null) {
                    angle = currentPosition.tilt.toDouble()
                }

                var animate = config.animate
                if (animate == null) {
                    animate = false
                }

                val updatedPosition =
                        CameraPosition.Builder()
                                .target(updatedTarget)
                                .zoom(zoom.toFloat())
                                .bearing(bearing.toFloat())
                                .tilt(angle.toFloat())
                                .build()

                if (animate) {
                    googleMap?.animateCamera(CameraUpdateFactory.newCameraPosition(updatedPosition))
                } else {
                    googleMap?.moveCamera(CameraUpdateFactory.newCameraPosition(updatedPosition))
                }
                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun getMapBounds(): Rect {
        return Rect(
                getScaledPixels(delegate.bridge, config.x),
                getScaledPixels(delegate.bridge, config.y),
                getScaledPixels(delegate.bridge, config.x + config.width),
                getScaledPixels(delegate.bridge, config.y + config.height)
        )
    }

    fun getLatLngBounds(): LatLngBounds {
        return googleMap?.projection?.visibleRegion?.latLngBounds ?: throw BoundsNotFoundError()
    }

    fun fitBounds(bounds: LatLngBounds, padding: Int) {
        val cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, padding)
        googleMap?.animateCamera(cameraUpdate)
    }

        private fun createLatLngBoundsFromGMSJS(boundsObject: JSObject): LatLngBounds {
        val southwestLatLng = LatLng(
            boundsObject.getDouble("south"),
            boundsObject.getDouble("west")
        )

        val northeastLatLng = LatLng(
            boundsObject.getDouble("north"),
            boundsObject.getDouble("east")
        )

        return LatLngBounds(southwestLatLng, northeastLatLng)
    }

    private fun getScaledPixels(bridge: Bridge, pixels: Int): Int {
        // Get the screen's density scale
        val scale = bridge.activity.resources.displayMetrics.density
        // Convert the dps to pixels, based on density scale
        return (pixels * scale + 0.5f).toInt()
    }

    private fun getScaledPixelsF(bridge: Bridge, pixels: Float): Float {
        // Get the screen's density scale
        val scale = bridge.activity.resources.displayMetrics.density
        // Convert the dps to pixels, based on density scale
        return (pixels * scale + 0.5f)
    }

    private fun getScaledRect(bridge: Bridge, rectF: RectF): RectF {
        return RectF(
                getScaledPixelsF(bridge, rectF.left),
                getScaledPixelsF(bridge, rectF.top),
                getScaledPixelsF(bridge, rectF.right),
                getScaledPixelsF(bridge, rectF.bottom)
        )
    }

    private fun buildCircle(circle: CapacitorGoogleMapsCircle): CircleOptions {
        val circleOptions = CircleOptions()
        circleOptions.fillColor(circle.fillColor)
        circleOptions.strokeColor(circle.strokeColor)
        circleOptions.strokeWidth(circle.strokeWidth)
        circleOptions.zIndex(circle.zIndex)
        circleOptions.clickable(circle.clickable)
        circleOptions.radius(circle.radius.toDouble())
        circleOptions.center(circle.center)

        return circleOptions
    }

    private fun buildPolygon(polygon: CapacitorGoogleMapsPolygon): PolygonOptions {
        val polygonOptions = PolygonOptions()
        polygonOptions.fillColor(polygon.fillColor)
        polygonOptions.strokeColor(polygon.strokeColor)
        polygonOptions.strokeWidth(polygon.strokeWidth)
        polygonOptions.zIndex(polygon.zIndex)
        polygonOptions.geodesic(polygon.geodesic)
        polygonOptions.clickable(polygon.clickable)

        var shapeCounter = 0
        polygon.shapes.forEach {
            if (shapeCounter == 0) {
                // outer shape
                it.forEach {
                    polygonOptions.add(it)
                }
            } else {
                polygonOptions.addHole(it)
            }

            shapeCounter += 1
        }

        return polygonOptions
    }
    
    private fun buildPolyline(line: CapacitorGoogleMapPolyline): PolylineOptions {
        val polylineOptions = PolylineOptions()
        polylineOptions.width(line.strokeWidth * this.config.devicePixelRatio)
        polylineOptions.color(line.strokeColor)
        polylineOptions.clickable(line.clickable)
        polylineOptions.zIndex(line.zIndex)
        polylineOptions.geodesic(line.geodesic)

        line.path.forEach {
            polylineOptions.add(it)
        }

        line.styleSpans.forEach {
            if (it.segments != null) {
                polylineOptions.addSpan(StyleSpan(it.color, it.segments))
            } else {
                polylineOptions.addSpan(StyleSpan(it.color))
            }
        }

        return polylineOptions
    }

    private fun buildMarker(marker: CapacitorGoogleMapMarker): MarkerOptions {
        val markerOptions = MarkerOptions()
        markerOptions.position(marker.coordinate)
        markerOptions.title(marker.title)
        markerOptions.snippet(marker.snippet)
        markerOptions.alpha(marker.opacity)
        markerOptions.rotation(marker.rotation)
        markerOptions.flat(marker.isFlat)
        markerOptions.draggable(marker.draggable)
        markerOptions.zIndex(marker.zIndex)
        if (marker.iconAnchor != null) {
            markerOptions.anchor(marker.iconAnchor!!.x, marker.iconAnchor!!.y)
        }


        if (!marker.iconUrl.isNullOrEmpty()) {
            if (this.markerIcons.contains(marker.iconUrl)) {
                val cachedBitmap = this.markerIcons[marker.iconUrl]
                markerOptions.icon(getResizedIcon(cachedBitmap!!, marker))
            } else {
                try {
                    var stream: InputStream? = null
                    if (marker.iconUrl!!.startsWith("https:")) {
                        stream = URL(marker.iconUrl).openConnection().getInputStream()
                    } else {
                        stream = this.delegate.context.assets.open("public/${marker.iconUrl}")
                    }
                    var bitmap = BitmapFactory.decodeStream(stream)
                    this.markerIcons[marker.iconUrl!!] = bitmap
                    markerOptions.icon(getResizedIcon(bitmap, marker))
                } catch (e: Exception) {
                    var detailedMessage = "${e.javaClass} - ${e.localizedMessage}"
                    if (marker.iconUrl!!.endsWith(".svg")) {
                        detailedMessage = "SVG not supported"
                    }

                    Log.w(
                            "CapacitorGoogleMaps",
                            "Could not load image '${marker.iconUrl}': ${detailedMessage}. Using default marker icon."
                    )
                }
            }
        } else {
            if (marker.colorHue != null) {
                markerOptions.icon(BitmapDescriptorFactory.defaultMarker(marker.colorHue!!))
            }
        }

        marker.markerOptions = markerOptions

        return markerOptions
    }

    private fun getResizedIcon(
            _bitmap: Bitmap,
            marker: CapacitorGoogleMapMarker
    ): BitmapDescriptor {
        var bitmap = _bitmap
        if (marker.iconSize != null) {
            bitmap =
                    Bitmap.createScaledBitmap(
                            bitmap,
                            (marker.iconSize!!.width * this.config.devicePixelRatio).toInt(),
                            (marker.iconSize!!.height * this.config.devicePixelRatio).toInt(),
                            false
                    )
        }
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    fun onStart() {
        mapView.onStart()
    }

    fun onResume() {
        mapView.onResume()
    }

    fun onStop() {
        mapView.onStop()
    }

    fun onPause() {
        mapView.onPause()
    }

    fun onDestroy() {
        mapView.onDestroy()
    }

    override fun onMapReady(map: GoogleMap) {
        runBlocking {
            googleMap = map

            val data = JSObject()
            data.put("mapId", this@CapacitorGoogleMap.id)
            delegate.notify("onMapReady", data)

            isReadyChannel.send(true)
            isReadyChannel.close()
        }
    }

    @SuppressLint("PotentialBehaviorOverride")
    fun setListeners() {
        CoroutineScope(Dispatchers.Main).launch {
            this@CapacitorGoogleMap.googleMap?.setOnCameraIdleListener(this@CapacitorGoogleMap)
            this@CapacitorGoogleMap.googleMap?.setOnCameraMoveStartedListener(
                    this@CapacitorGoogleMap
            )
            this@CapacitorGoogleMap.googleMap?.setOnCameraMoveListener(this@CapacitorGoogleMap)
            this@CapacitorGoogleMap.googleMap?.setOnMarkerClickListener(this@CapacitorGoogleMap)
            this@CapacitorGoogleMap.googleMap?.setOnPolygonClickListener(this@CapacitorGoogleMap)
            this@CapacitorGoogleMap.googleMap?.setOnCircleClickListener(this@CapacitorGoogleMap)
            this@CapacitorGoogleMap.googleMap?.setOnMarkerDragListener(this@CapacitorGoogleMap)
            this@CapacitorGoogleMap.googleMap?.setOnMapClickListener(this@CapacitorGoogleMap)
            this@CapacitorGoogleMap.googleMap?.setOnMyLocationButtonClickListener(
                    this@CapacitorGoogleMap
            )
            this@CapacitorGoogleMap.googleMap?.setOnMyLocationClickListener(this@CapacitorGoogleMap)
            this@CapacitorGoogleMap.googleMap?.setOnInfoWindowClickListener(this@CapacitorGoogleMap)
            this@CapacitorGoogleMap.googleMap?.setOnPolylineClickListener(this@CapacitorGoogleMap)
        }
    }

    fun setClusterListeners() {
        CoroutineScope(Dispatchers.Main).launch {
            clusterManager?.setOnClusterItemClickListener {
                if (null == it.googleMapMarker) false
                else this@CapacitorGoogleMap.onMarkerClick(it.googleMapMarker!!)
            }

            clusterManager?.setOnClusterItemInfoWindowClickListener {
                if (null != it.googleMapMarker) {
                    this@CapacitorGoogleMap.onInfoWindowClick(it.googleMapMarker!!)
                }
            }

            clusterManager?.setOnClusterInfoWindowClickListener {
                val data = this@CapacitorGoogleMap.getClusterData(it)
                delegate.notify("onClusterInfoWindowClick", data)
            }

            clusterManager?.setOnClusterClickListener {
                val data = this@CapacitorGoogleMap.getClusterData(it)
                delegate.notify("onClusterClick", data)
                false
            }
        }
    }

    private fun getClusterData(it: Cluster<CapacitorGoogleMapMarker>): JSObject {
        val data = JSObject()
        data.put("mapId", this.id)
        data.put("latitude", it.position.latitude)
        data.put("longitude", it.position.longitude)
        data.put("size", it.size)

        val items = JSArray()
        for (item in it.items) {
            val marker = item.googleMapMarker

            if (marker != null) {
                val jsItem = JSObject()
                jsItem.put("markerId", marker.id)
                jsItem.put("latitude", marker.position.latitude)
                jsItem.put("longitude", marker.position.longitude)
                jsItem.put("title", marker.title)
                jsItem.put("snippet", marker.snippet)

                items.put(jsItem)
            }
        }

        data.put("items", items)

        return data
    }

        private fun GeoJsonFeature.boundingBoxFromGeometry(): LatLngBounds? {
        val coordinates: MutableList<LatLng> = ArrayList()

        if (this.hasGeometry()) {
            when (geometry.geometryType) {
                "Point" -> coordinates.add((geometry as GeoJsonPoint).coordinates)
                "MultiPoint" -> {
                    val points = (geometry as GeoJsonMultiPoint).points
                    for (point in points) {
                        coordinates.add(point.coordinates)
                    }
                }

                "LineString" -> coordinates.addAll((geometry as GeoJsonLineString).coordinates)
                "MultiLineString" -> {
                    val lines = (geometry as GeoJsonMultiLineString).lineStrings
                    for (line in lines) {
                        coordinates.addAll(line.coordinates)
                    }
                }

                "Polygon" -> {
                    val lists = (geometry as GeoJsonPolygon).coordinates
                    for (list in lists) {
                        coordinates.addAll(list)
                    }
                }

                "MultiPolygon" -> {
                    val polygons = (geometry as GeoJsonMultiPolygon).polygons
                    for (polygon in polygons) {
                        for (list in polygon.coordinates) {
                            coordinates.addAll(list)
                        }
                    }
                }
            }
        }

        val builder = LatLngBounds.builder()
        for (latLng in coordinates) {
            builder.include(latLng)
        }
        return builder.build()
    }

    override fun onMapClick(point: LatLng) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("latitude", point.latitude)
        data.put("longitude", point.longitude)
        delegate.notify("onMapClick", data)
    }

    override fun onMarkerClick(marker: Marker): Boolean {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("markerId", marker.id)
        data.put("latitude", marker.position.latitude)
        data.put("longitude", marker.position.longitude)
        data.put("title", marker.title)
        data.put("snippet", marker.snippet)
        delegate.notify("onMarkerClick", data)
        return false
    }

    override fun onPolylineClick(polyline: Polyline) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("polylineId", polyline.id)
        data.put("tag", polyline.tag)
        delegate.notify("onPolylineClick", data)
    }

    override fun onMarkerDrag(marker: Marker) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("markerId", marker.id)
        data.put("latitude", marker.position.latitude)
        data.put("longitude", marker.position.longitude)
        data.put("title", marker.title)
        data.put("snippet", marker.snippet)
        delegate.notify("onMarkerDrag", data)
    }

    override fun onMarkerDragStart(marker: Marker) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("markerId", marker.id)
        data.put("latitude", marker.position.latitude)
        data.put("longitude", marker.position.longitude)
        data.put("title", marker.title)
        data.put("snippet", marker.snippet)
        delegate.notify("onMarkerDragStart", data)
    }

    override fun onMarkerDragEnd(marker: Marker) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("markerId", marker.id)
        data.put("latitude", marker.position.latitude)
        data.put("longitude", marker.position.longitude)
        data.put("title", marker.title)
        data.put("snippet", marker.snippet)
        delegate.notify("onMarkerDragEnd", data)
    }

    override fun onMyLocationButtonClick(): Boolean {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        delegate.notify("onMyLocationButtonClick", data)
        return false
    }

    override fun onMyLocationClick(location: Location) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("latitude", location.latitude)
        data.put("longitude", location.longitude)
        delegate.notify("onMyLocationClick", data)
    }

    override fun onCameraIdle() {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("bounds", getLatLngBoundsJSObject(getLatLngBounds()))
        data.put("bearing", this@CapacitorGoogleMap.googleMap?.cameraPosition?.bearing)
        data.put("latitude", this@CapacitorGoogleMap.googleMap?.cameraPosition?.target?.latitude)
        data.put("longitude", this@CapacitorGoogleMap.googleMap?.cameraPosition?.target?.longitude)
        data.put("tilt", this@CapacitorGoogleMap.googleMap?.cameraPosition?.tilt)
        data.put("zoom", this@CapacitorGoogleMap.googleMap?.cameraPosition?.zoom)
        delegate.notify("onCameraIdle", data)
        delegate.notify("onBoundsChanged", data)
    }

    override fun onCameraMoveStarted(reason: Int) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("isGesture", reason == 1)
        delegate.notify("onCameraMoveStarted", data)
    }

    override fun onInfoWindowClick(marker: Marker) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("markerId", marker.id)
        data.put("latitude", marker.position.latitude)
        data.put("longitude", marker.position.longitude)
        data.put("title", marker.title)
        data.put("snippet", marker.snippet)
        delegate.notify("onInfoWindowClick", data)
    }

    override fun onCameraMove() {
        debounceJob?.cancel()
        debounceJob = CoroutineScope(Dispatchers.Main).launch {
            delay(100)
            clusterManager?.cluster()
        }
    }

    override fun onPolygonClick(polygon: Polygon) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("polygonId", polygon.id)
        data.put("tag", polygon.tag)
        delegate.notify("onPolygonClick", data)
    }

    override fun onCircleClick(circle: Circle) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("circleId", circle.id)
        data.put("tag", circle.tag)
        data.put("latitude", circle.center.latitude)
        data.put("longitude", circle.center.longitude)
        data.put("radius", circle.radius)

        delegate.notify("onCircleClick", data)
    }
}

fun getLatLngBoundsJSObject(bounds: LatLngBounds): JSObject {
    val data = JSObject()

    val southwestJS = JSObject()
    val centerJS = JSObject()
    val northeastJS = JSObject()

    southwestJS.put("lat", bounds.southwest.latitude)
    southwestJS.put("lng", bounds.southwest.longitude)
    centerJS.put("lat", bounds.center.latitude)
    centerJS.put("lng", bounds.center.longitude)
    northeastJS.put("lat", bounds.northeast.latitude)
    northeastJS.put("lng", bounds.northeast.longitude)

    data.put("southwest", southwestJS)
    data.put("center", centerJS)
    data.put("northeast", northeastJS)

    return data
}
