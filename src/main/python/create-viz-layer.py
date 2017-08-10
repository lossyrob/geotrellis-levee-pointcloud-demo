import argparse
import os
from pyspark import SparkContext

import geopyspark as gps


def create_viz_layers(catalog_path, input_layer_names, output_layer_names, num_partitions=None):
    if len(input_layer_names) != len(output_layer_names):
        raise Exception("Input layer name count must match output layer name count.")

    abs_path = os.path.abspath(catalog_path)
    if not os.path.exists(abs_path):
        raise Exception("GeoTrellis catalog does not exit at {}".format(abs_path))
    catalog_uri = "file://{}".format(abs_path)

    print("Catalog: {}".format(catalog_uri))

    for input_layer_name, output_layer_name in zip(input_layer_names, output_layer_names):
        layer = gps.query(catalog_uri, input_layer_name, 0, num_partitions=num_partitions)

        reprojected = layer.tile_to_layout(target_crs="EPSG:3857",
                                           layout=gps.GlobalLayout(tile_size=256),
                                           resample_method=gps.ResampleMethod.BILINEAR)

        # carry forward the histogram from original layer
        store = gps.geotrellis.catalog.AttributeStore(catalog_uri)
        histogram_dict = store.layer(input_layer_name, 0).read("histogram")
        store.layer(output_layer_name, 0).write("histogram", histogram_dict)

        # pyramid and save reprojected layer
        pyramided = reprojected.pyramid()
        for tiled in pyramided.levels.values():
            store.layer(output_layer_name, tiled.zoom_level).delete('metadata')
            gps.write(catalog_uri, output_layer_name, tiled)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Create a viz layer from ingested layers.')
    parser.add_argument('--input_layers', metavar='INPUT LAYERS', nargs='+',
                        required=True,
                        help='List of input layers to create visual layers for')
    parser.add_argument('--output_layers', metavar='OUTPUT LAYERS', nargs='+',
                        required=True,
                        help='List of output layers to create visual layers for (must be same number as input layers')
    parser.add_argument('--num_partitions', metavar="NUM PARTITIONS", type=int,
                        default=None,
                        help='number of partitions to use')
    parser.add_argument('catalog_path', metavar='CATALOG PATH',
                        help='Path to the GeoTrellis Catalog')

    args = parser.parse_args()

    conf = gps.geopyspark_conf(appName="create_viz_layer")
    sc = SparkContext(conf=conf)
    sc.setLogLevel("ERROR")

    #Environments starts with context objects
    print("Spark objects")
    print(sc)

    try:
        create_viz_layers(args.catalog_path,
                          args.input_layers,
                          args.output_layers,
                          args.num_partitions)
    finally:
        sc.stop()
