/* eslint-disable @typescript-eslint/no-namespace */
/* eslint-disable @typescript-eslint/no-explicit-any */
import {
  LatLngBounds,
  MapType,
  Marker,
  Polygon,
  Circle,
  Polyline,
  StyleSpan,
  FeatureType,
  FeatureStyles,
} from './definitions';
import { GoogleMap } from './map';

export { GoogleMap, LatLngBounds, MapType, Marker, Polygon, Circle, Polyline, StyleSpan, FeatureType, FeatureStyles };

declare global {
  export namespace JSX {
    export interface IntrinsicElements {
      'capacitor-google-map': any;
    }
  }
}
