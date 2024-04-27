import { WebPlugin } from '@capacitor/core';
import type { Cluster, onClusterClickHandler } from '@googlemaps/markerclusterer';
import { MarkerClusterer, SuperClusterAlgorithm } from '@googlemaps/markerclusterer';

import type { LatLngBoundsInterface, LatLng, Marker, MapPadding, GoogleMapConfig } from './definitions';
import { FeatureType, LatLngBounds } from './definitions';
import type {
  AddTileOverlayArgs,
  AddMarkerArgs,
  CameraArgs,
  AddMarkersArgs,
  CapacitorGoogleMapsPlugin,
  CreateMapArgs,
  DestroyMapArgs,
  RemoveMarkerArgs,
  RemoveMarkersArgs,
  MapBoundsContainsArgs,
  EnableClusteringArgs,
  FitBoundsArgs,
  MapBoundsExtendArgs,
  AddPolygonsArgs,
  RemovePolygonsArgs,
  AddCirclesArgs,
  RemoveCirclesArgs,
  AddPolylinesArgs,
  RemovePolylinesArgs,
  AddFeatureArgs,
  GetFeatureBoundsArgs,
  RemoveFeatureArgs,
  UpdateMapArgs,
  RawGoogleMapInstanceArgs,
} from './implementation';

class MapInstance {
  element!: HTMLElement;
  map!: google.maps.Map;
  markers: {
    [id: string]: google.maps.Marker;
  } = {};
  polygons: {
    [id: string]: google.maps.Polygon;
  } = {};
  circles: {
    [id: string]: google.maps.Circle;
  } = {};
  polylines: {
    [id: string]: google.maps.Polyline;
  } = {};
  markerClusterer?: MarkerClusterer;
  trafficLayer?: google.maps.TrafficLayer;
}

class CoordMapType implements google.maps.MapType {
  tileSize: google.maps.Size;
  alt: string | null = null;
  maxZoom = 17;
  minZoom = 0;
  name: string | null = null;
  projection: google.maps.Projection | null = null;
  radius = 6378137;

  constructor(tileSize: google.maps.Size) {
    this.tileSize = tileSize;
  }
  getTile(coord: google.maps.Point, zoom: number, ownerDocument: Document): HTMLElement {
    const div = ownerDocument.createElement('div');
    const pElement = ownerDocument.createElement('p');
    pElement.innerHTML = `x = ${coord.x}, y = ${coord.y}, zoom = ${zoom}`;
    pElement.style.color = 'rgba(0, 0, 0, 0.5)';
    pElement.style.padding = '0 20px';
    div.appendChild(pElement);

    div.style.width = this.tileSize.width + 'px';
    div.style.height = this.tileSize.height + 'px';
    div.style.fontSize = '10';
    div.style.borderStyle = 'solid';
    div.style.borderWidth = '1px';
    div.style.borderColor = 'rgba(0, 0, 0, 0.5)';
    return div;
  }
  // eslint-disable-next-line @typescript-eslint/no-empty-function
  releaseTile(): void {}
}

export class CapacitorGoogleMapsWeb extends WebPlugin implements CapacitorGoogleMapsPlugin {
  private gMapsRef: google.maps.MapsLibrary | undefined = undefined;
  private maps: { [id: string]: MapInstance } = {};
  private currMarkerId = 0;
  private currPolygonId = 0;
  private currCircleId = 0;
  private currPolylineId = 0;

  private onClusterClickHandler: onClusterClickHandler = (
    _: google.maps.MapMouseEvent,
    cluster: Cluster,
    map: google.maps.Map
  ): void => {
    const mapId = this.getIdFromMap(map);
    const items: any[] = [];

    if (cluster.markers != undefined) {
      for (const marker of cluster.markers) {
        const markerId = this.getIdFromMarker(mapId, marker);

        items.push({
          markerId: markerId,
          latitude: marker.getPosition()?.lat(),
          longitude: marker.getPosition()?.lng(),
          title: marker.getTitle(),
          snippet: '',
        });
      }
    }

    this.notifyListeners('onClusterClick', {
      mapId: mapId,
      latitude: cluster.position.lat(),
      longitude: cluster.position.lng(),
      size: cluster.count,
      items: items,
    });
  };

  private getIdFromMap(map: google.maps.Map): string {
    for (const id in this.maps) {
      if (this.maps[id].map == map) {
        return id;
      }
    }

    return '';
  }

  private getIdFromMarker(mapId: string, marker: google.maps.Marker): string {
    for (const id in this.maps[mapId].markers) {
      if (this.maps[mapId].markers[id] == marker) {
        return id;
      }
    }

    return '';
  }

  private async importGoogleLib(apiKey: string, region?: string, language?: string) {
    if (this.gMapsRef === undefined) {
      const lib = await import('@googlemaps/js-api-loader');
      const loader = new lib.Loader({
        apiKey: apiKey ?? '',
        version: 'weekly',
        language,
        region,
      });
      this.gMapsRef = await loader.importLibrary('maps');
      console.log('Loaded google maps API');
    }
  }

  async enableTouch(_args: { id: string }): Promise<void> {
    this.maps[_args.id].map.setOptions({ gestureHandling: 'auto' });
  }

  async disableTouch(_args: { id: string }): Promise<void> {
    this.maps[_args.id].map.setOptions({ gestureHandling: 'none' });
  }

  async setCamera(_args: CameraArgs): Promise<void> {
    // Animation not supported yet...
    this.maps[_args.id].map.moveCamera({
      center: _args.config.coordinate,
      heading: _args.config.bearing,
      tilt: _args.config.angle,
      zoom: _args.config.zoom,
    });
  }

  dispatchMapEvent(): Promise<void> {
    throw new Error('Method not supported on web.');
  }

  async getMapBounds(_args: { id: string }): Promise<LatLngBounds> {
    const bounds = this.maps[_args.id].map.getBounds();

    if (!bounds) {
      throw new Error('Google Map Bounds could not be found.');
    }

    return new LatLngBounds({
      southwest: {
        lat: bounds.getSouthWest().lat(),
        lng: bounds.getSouthWest().lng(),
      },
      center: {
        lat: bounds.getCenter().lat(),
        lng: bounds.getCenter().lng(),
      },
      northeast: {
        lat: bounds.getNorthEast().lat(),
        lng: bounds.getNorthEast().lng(),
      },
    });
  }

  async fitBounds(_args: FitBoundsArgs): Promise<void> {
    const map = this.maps[_args.id].map;
    const bounds = this.getLatLngBounds(_args.bounds);
    map.fitBounds(bounds, _args.padding);
  }

  async addTileOverlay(_args: AddTileOverlayArgs): Promise<any> {
    const map = this.maps[_args.id].map;

    const tileSize = new google.maps.Size(256, 256); // Create a google.maps.Size instance
    const coordMapType = new CoordMapType(tileSize);

    // Create a TileOverlay object
    const customMapOverlay = new google.maps.ImageMapType({
      getTileUrl: function (coord, zoom) {
        return _args.getTile(coord.x, coord.y, zoom);
      },
      tileSize: new google.maps.Size(256, 256),
      opacity: _args?.opacity,
      name: 'tileoverlay',
    });

    // Draw Tiles
    map.overlayMapTypes.insertAt(0, coordMapType); // insert coordMapType at the first position

    // Add the TileOverlay to the map
    map.overlayMapTypes.push(customMapOverlay);

    // Optionally, you can set debug mode if needed
    if (_args?.debug) {
      map.addListener('mousemove', function (event: any) {
        console.log('Mouse Coordinates: ', event.latLng.toString());
      });
    }

    // Set visibility based on the 'visible' property
    if (!_args?.visible) {
      map.overlayMapTypes.pop(); // Remove the last overlay (customMapOverlay) from the stack
    }

    // Set zIndex based on the 'zIndex' property
    if (_args?.zIndex !== undefined) {
      // Move the customMapOverlay to the specified index in the overlay stack
      map.overlayMapTypes.setAt(map.overlayMapTypes.getLength() - 1, customMapOverlay);
    }
  }

  async addMarkers(_args: AddMarkersArgs): Promise<{ ids: string[] }> {
    const markerIds: string[] = [];
    const map = this.maps[_args.id];

    for (const markerArgs of _args.markers) {
      const markerOpts = this.buildMarkerOpts(markerArgs, map.map);
      const marker = new google.maps.Marker(markerOpts);

      const id = '' + this.currMarkerId;

      map.markers[id] = marker;
      this.setMarkerListeners(_args.id, id, marker);

      markerIds.push(id);
      this.currMarkerId++;
    }

    return { ids: markerIds };
  }

  async addMarker(_args: AddMarkerArgs): Promise<{ id: string }> {
    const markerOpts = this.buildMarkerOpts(_args.marker, this.maps[_args.id].map);

    const marker = new google.maps.Marker(markerOpts);

    const id = '' + this.currMarkerId;

    this.maps[_args.id].markers[id] = marker;
    this.setMarkerListeners(_args.id, id, marker);

    this.currMarkerId++;

    return { id: id };
  }

  async removeMarkers(_args: RemoveMarkersArgs): Promise<void> {
    const map = this.maps[_args.id];

    for (const id of _args.markerIds) {
      map.markers[id].setMap(null);
      delete map.markers[id];
    }
  }

  async removeMarker(_args: RemoveMarkerArgs): Promise<void> {
    this.maps[_args.id].markers[_args.markerId].setMap(null);
    delete this.maps[_args.id].markers[_args.markerId];
  }

  async addPolygons(args: AddPolygonsArgs): Promise<{ ids: string[] }> {
    const polygonIds: string[] = [];
    const map = this.maps[args.id];

    for (const polygonArgs of args.polygons) {
      const polygon = new google.maps.Polygon(polygonArgs);
      polygon.setMap(map.map);

      const id = '' + this.currPolygonId;
      this.maps[args.id].polygons[id] = polygon;
      this.setPolygonListeners(args.id, id, polygon);

      polygonIds.push(id);
      this.currPolygonId++;
    }

    return { ids: polygonIds };
  }

  async removePolygons(args: RemovePolygonsArgs): Promise<void> {
    const map = this.maps[args.id];

    for (const id of args.polygonIds) {
      map.polygons[id].setMap(null);
      delete map.polygons[id];
    }
  }

  async addCircles(args: AddCirclesArgs): Promise<{ ids: string[] }> {
    const circleIds: string[] = [];
    const map = this.maps[args.id];

    for (const circleArgs of args.circles) {
      const circle = new google.maps.Circle(circleArgs);
      circle.setMap(map.map);

      const id = '' + this.currCircleId;
      this.maps[args.id].circles[id] = circle;
      this.setCircleListeners(args.id, id, circle);

      circleIds.push(id);
      this.currCircleId++;
    }

    return { ids: circleIds };
  }

  async removeCircles(args: RemoveCirclesArgs): Promise<void> {
    const map = this.maps[args.id];

    for (const id of args.circleIds) {
      map.circles[id].setMap(null);
      delete map.circles[id];
    }
  }

  async addPolylines(args: AddPolylinesArgs): Promise<{ ids: string[] }> {
    const lineIds: string[] = [];
    const map = this.maps[args.id];

    for (const polylineArgs of args.polylines) {
      const polyline = new google.maps.Polyline(polylineArgs);
      polyline.set('tag', polylineArgs.tag);
      polyline.setMap(map.map);

      const id = '' + this.currPolylineId;
      this.maps[args.id].polylines[id] = polyline;
      this.setPolylineListeners(args.id, id, polyline);

      lineIds.push(id);
      this.currPolylineId++;
    }

    return {
      ids: lineIds,
    };
  }

  async removePolylines(args: RemovePolylinesArgs): Promise<void> {
    const map = this.maps[args.id];

    for (const id of args.polylineIds) {
      map.polylines[id].setMap(null);
      delete map.polylines[id];
    }
  }

  async addFeatures(args: AddFeatureArgs): Promise<{ ids: string[] }> {
    const featureIds: string[] = [];
    const map = this.maps[args.id];

    if (args.type === FeatureType.GeoJSON) {
      featureIds.push(
        ...(map.map.data
          .addGeoJson(args.data, args.idPropertyName ? { idPropertyName: args.idPropertyName } : null)
          .map((f) => f.getId())
          .filter((f) => f !== undefined)
          .map((f) => f?.toString()) as string[])
      );
    } else {
      const featureId = map.map.data.add(args.data).getId();
      if (featureId) {
        featureIds.push(featureId.toString());
      }
    }

    if (args.styles) {
      map.map.data.setStyle((feature) => {
        const featureId = feature.getId();
        return featureId ? (args.styles?.[featureId] as any) : null;
      });
    }

    return {
      ids: featureIds,
    };
  }

  async getFeatureBounds(args: GetFeatureBoundsArgs): Promise<{ bounds: LatLngBounds }> {
    if (!args.featureId) {
      throw new Error('Feature id not set.');
    }

    const map = this.maps[args.id];
    const feature = map.map.data.getFeatureById(args.featureId);

    if (!feature) {
      throw new Error(`Feature '${args.featureId}' could not be found.`);
    }

    const bounds = new google.maps.LatLngBounds();

    feature?.getGeometry()?.forEachLatLng((latLng) => {
      bounds.extend(latLng);
    });

    return {
      bounds: new LatLngBounds({
        southwest: bounds.getSouthWest().toJSON() as LatLng,
        center: bounds.getCenter().toJSON() as LatLng,
        northeast: bounds.getNorthEast().toJSON() as LatLng,
      } as LatLngBoundsInterface),
    };
  }

  async removeFeature(args: RemoveFeatureArgs): Promise<void> {
    if (!args.featureId) {
      throw new Error('Feature id not set.');
    }

    const map = this.maps[args.id];

    const feature = map.map.data.getFeatureById(args.featureId);
    if (!feature) {
      throw new Error(`Feature '${args.featureId}' could not be found.`);
    }

    map.map.data.remove(feature);
  }

  async enableClustering(_args: EnableClusteringArgs): Promise<void> {
    const markers: google.maps.Marker[] = [];

    for (const id in this.maps[_args.id].markers) {
      markers.push(this.maps[_args.id].markers[id]);
    }

    this.maps[_args.id].markerClusterer = new MarkerClusterer({
      map: this.maps[_args.id].map,
      markers: markers,
      algorithm: new SuperClusterAlgorithm({
        minPoints: _args.minClusterSize ?? 4,
      }),
      onClusterClick: this.onClusterClickHandler,
    });
  }

  async disableClustering(_args: { id: string }): Promise<void> {
    this.maps[_args.id].markerClusterer?.setMap(null);
    this.maps[_args.id].markerClusterer = undefined;
  }

  async onScroll(): Promise<void> {
    throw new Error('Method not supported on web.');
  }

  async onResize(): Promise<void> {
    throw new Error('Method not supported on web.');
  }

  async onDisplay(): Promise<void> {
    throw new Error('Method not supported on web.');
  }

  async create(_args: CreateMapArgs): Promise<void> {
    console.log(`Create map: ${_args.id}`);
    await this.importGoogleLib(_args.apiKey, _args.region, _args.language);

    const mapInstance = {
      map: new window.google.maps.Map(_args.element, { ..._args.config }),
      element: _args.element,
      markers: {},
      polygons: {},
      circles: {},
      polylines: {},
    };
    this.applyConfig(mapInstance, _args.config);
    this.maps[_args.id] = mapInstance;
    this.setMapListeners(_args.id);
  }

  async update(_args: UpdateMapArgs): Promise<void> {
    const mapInstance = this.maps[_args.id];
    mapInstance.map.setOptions(_args.config);

    this.applyConfig(mapInstance, _args.config);
  }

  private applyConfig(mapInstance: MapInstance, config: GoogleMapConfig): void {
    if (config.isMyLocationEnabled) {
      this.enableMyLocation(mapInstance);
    }

    if (config.isTrafficLayerEnabled !== undefined) {
      this.setTrafficLayer(mapInstance, config.isTrafficLayerEnabled);
    }

    if (config.mapTypeId !== undefined) {
      this.setMapTypeId(mapInstance, config.mapTypeId as string);
    }

    if (config.padding !== undefined) {
      this.setPadding(mapInstance, config.padding);
    }
  }

  private enableMyLocation(mapInstance: MapInstance): void {
    if (navigator.geolocation) {
      navigator.geolocation.getCurrentPosition(
        (position: GeolocationPosition) => {
          const pos = {
            lat: position.coords.latitude,
            lng: position.coords.longitude,
          };

          mapInstance.map.setCenter(pos);

          this.notifyListeners('onMyLocationButtonClick', {});

          this.notifyListeners('onMyLocationClick', {});
        },
        () => {
          throw new Error('Geolocation not supported on web browser.');
        }
      );
    } else {
      throw new Error('Geolocation not supported on web browser.');
    }
  }

  private setTrafficLayer(mapInstance: MapInstance, enabled: boolean): void {
    const trafficLayer = mapInstance.trafficLayer ?? new google.maps.TrafficLayer();

    if (enabled) {
      trafficLayer.setMap(mapInstance.map);
      mapInstance.trafficLayer = trafficLayer;
    } else if (mapInstance.trafficLayer) {
      trafficLayer.setMap(null);
      mapInstance.trafficLayer = undefined;
    }
  }

  private setMapTypeId(mapInstance: MapInstance, typeId: string): void {
    mapInstance.map.setMapTypeId(typeId);
  }

  public getRawGoogleMapInstance(_args: RawGoogleMapInstanceArgs): google.maps.Map {
    return this.maps[_args.id].map;
  }

  private setPadding(mapInstance: MapInstance, padding: MapPadding): void {
    const bounds = mapInstance.map.getBounds();

    if (bounds !== undefined) {
      mapInstance.map.fitBounds(bounds, padding);
    }
  }

  async destroy(_args: DestroyMapArgs): Promise<void> {
    console.log(`Destroy map: ${_args.id}`);
    const mapItem = this.maps[_args.id];
    mapItem.element.innerHTML = '';
    mapItem.map.unbindAll();
    delete this.maps[_args.id];
  }

  async mapBoundsContains(_args: MapBoundsContainsArgs): Promise<{ contains: boolean }> {
    const bounds = this.getLatLngBounds(_args.bounds);
    const point = new google.maps.LatLng(_args.point.lat, _args.point.lng);
    return { contains: bounds.contains(point) };
  }

  async mapBoundsExtend(_args: MapBoundsExtendArgs): Promise<{ bounds: LatLngBounds }> {
    const bounds = this.getLatLngBounds(_args.bounds);
    const point = new google.maps.LatLng(_args.point.lat, _args.point.lng);
    bounds.extend(point);
    const result = new LatLngBounds({
      southwest: {
        lat: bounds.getSouthWest().lat(),
        lng: bounds.getSouthWest().lng(),
      },
      center: {
        lat: bounds.getCenter().lat(),
        lng: bounds.getCenter().lng(),
      },
      northeast: {
        lat: bounds.getNorthEast().lat(),
        lng: bounds.getNorthEast().lng(),
      },
    });
    return { bounds: result };
  }

  private getLatLngBounds(_args: LatLngBounds): google.maps.LatLngBounds {
    return new google.maps.LatLngBounds(
      new google.maps.LatLng(_args.southwest.lat, _args.southwest.lng),
      new google.maps.LatLng(_args.northeast.lat, _args.northeast.lng)
    );
  }

  async setCircleListeners(mapId: string, circleId: string, circle: google.maps.Circle): Promise<void> {
    circle.addListener('click', () => {
      this.notifyListeners('onCircleClick', {
        mapId: mapId,
        circleId: circleId,
        tag: circle.get('tag'),
      });
    });
  }

  async setPolygonListeners(mapId: string, polygonId: string, polygon: google.maps.Polygon): Promise<void> {
    polygon.addListener('click', () => {
      this.notifyListeners('onPolygonClick', {
        mapId: mapId,
        polygonId: polygonId,
        tag: polygon.get('tag'),
      });
    });
  }

  async setPolylineListeners(mapId: string, polylineId: string, polyline: google.maps.Polyline): Promise<void> {
    polyline.addListener('click', () => {
      this.notifyListeners('onPolylineClick', {
        mapId: mapId,
        polylineId: polylineId,
        tag: polyline.get('tag'),
      });
    });
  }

  async setMarkerListeners(mapId: string, markerId: string, marker: google.maps.Marker): Promise<void> {
    marker.addListener('click', () => {
      this.notifyListeners('onMarkerClick', {
        mapId: mapId,
        markerId: markerId,
        latitude: marker.getPosition()?.lat(),
        longitude: marker.getPosition()?.lng(),
        title: marker.getTitle(),
        snippet: '',
      });
    });

    marker.addListener('dragstart', () => {
      this.notifyListeners('onMarkerDragStart', {
        mapId: mapId,
        markerId: markerId,
        latitude: marker.getPosition()?.lat(),
        longitude: marker.getPosition()?.lng(),
        title: marker.getTitle(),
        snippet: '',
      });
    });

    marker.addListener('drag', () => {
      this.notifyListeners('onMarkerDrag', {
        mapId: mapId,
        markerId: markerId,
        latitude: marker.getPosition()?.lat(),
        longitude: marker.getPosition()?.lng(),
        title: marker.getTitle(),
        snippet: '',
      });
    });

    marker.addListener('dragend', () => {
      this.notifyListeners('onMarkerDragEnd', {
        mapId: mapId,
        markerId: markerId,
        latitude: marker.getPosition()?.lat(),
        longitude: marker.getPosition()?.lng(),
        title: marker.getTitle(),
        snippet: '',
      });
    });
  }

  async setMapListeners(mapId: string): Promise<void> {
    const map = this.maps[mapId].map;

    map.addListener('idle', async () => {
      const bounds = await this.getMapBounds({ id: mapId });
      this.notifyListeners('onCameraIdle', {
        mapId: mapId,
        bearing: map.getHeading(),
        bounds: bounds,
        latitude: map.getCenter()?.lat(),
        longitude: map.getCenter()?.lng(),
        tilt: map.getTilt(),
        zoom: map.getZoom(),
      });
    });

    map.addListener('center_changed', () => {
      this.notifyListeners('onCameraMoveStarted', {
        mapId: mapId,
        isGesture: true,
      });
    });

    map.addListener('bounds_changed', async () => {
      const bounds = await this.getMapBounds({ id: mapId });
      this.notifyListeners('onBoundsChanged', {
        mapId: mapId,
        bearing: map.getHeading(),
        bounds: bounds,
        latitude: map.getCenter()?.lat(),
        longitude: map.getCenter()?.lng(),
        tilt: map.getTilt(),
        zoom: map.getZoom(),
      });
    });

    map.addListener('click', (e: google.maps.MapMouseEvent | google.maps.IconMouseEvent) => {
      this.notifyListeners('onMapClick', {
        mapId: mapId,
        latitude: e.latLng?.lat(),
        longitude: e.latLng?.lng(),
      });
    });

    this.notifyListeners('onMapReady', {
      mapId: mapId,
    });
  }

  private buildMarkerOpts(marker: Marker, map: google.maps.Map): google.maps.MarkerOptions {
    let iconImage: google.maps.Icon | undefined = undefined;
    if (marker.iconUrl) {
      iconImage = {
        url: marker.iconUrl,
        scaledSize: marker.iconSize ? new google.maps.Size(marker.iconSize.width, marker.iconSize.height) : null,
        anchor: marker.iconAnchor
          ? new google.maps.Point(marker.iconAnchor.x, marker.iconAnchor.y)
          : new google.maps.Point(0, 0),
        origin: marker.iconOrigin
          ? new google.maps.Point(marker.iconOrigin.x, marker.iconOrigin.y)
          : new google.maps.Point(0, 0),
      };
    }

    const opts: google.maps.MarkerOptions = {
      position: marker.coordinate,
      map: map,
      opacity: marker.opacity,
      title: marker.title,
      icon: iconImage,
      draggable: marker.draggable,
      zIndex: marker.zIndex ?? 0,
    };

    return opts;
  }
}
