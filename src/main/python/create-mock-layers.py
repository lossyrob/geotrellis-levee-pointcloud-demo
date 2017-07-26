import argparse
import os
from pyspark import SparkContext
import numpy as np

import geopyspark as gps


def create_mock_layers(catalog_path, input_layer_names, output_layer_names, num_partitions=None):
    if len(input_layer_names) != len(output_layer_names):
        raise Exception("Input layer name count must match output layer name count.")

    abs_path = os.path.abspath(catalog_path)
    if not os.path.exists(abs_path):
        raise Exception("GeoTrellis catalog does not exit at {}".format(abs_path))
    catalog_uri = "file://{}".format(abs_path)

    print("Catalog: {}".format(catalog_uri))

    for input_layer_name, output_layer_name in zip(input_layer_names, output_layer_names):
        layer = gps.query(gps.LayerType.SPATIAL,
                          catalog_uri,
                          input_layer_name,
                          0,
                          num_partitions=num_partitions)
        layer_metadata = layer.layer_metadata

        # Compute the mean of the layer, and add the deviation
        # from the mean to the values

        rdd = layer.to_numpy_rdd()

        def compute_mean(tile):
            data = tile.cells[0]

            masked = np.ma.masked_where(data == tile.no_data_value, data)
            sum = np.sum(masked)
            count = np.sum(data != tile.no_data_value)

            return (sum, count)

        def reduce_mean(v1, v2):
            return (v1[0] + v2[0], v1[1] + v2[1])

        (sum, count) = rdd.map(lambda v: compute_mean(v[1])) \
                          .reduce(reduce_mean)

        mean = sum / count

        def transform_data(tile):
            data = tile.cells[0]
            masked = np.ma.masked_where(data == tile.no_data_value, data)
            masked_deviated = masked + (masked - mean)
            result = np.full(data.shape, tile.no_data_value)
            result[np.ma.where(data != tile.no_data_value)] = masked_dev[np.ma.where(data != tile.no_data_value)]

            return gps.Tile.from_numpy_array(np.array([result]), no_data_value=tile.no_data_value)

        deviated = rdd.mapValues(transform_data)

        result = gps.TiledRasterLayer.from_numpy_rdd(gps.LayerType.SPATIAL, deviated, layer_metadata)

        gps.write(catalog_uri, output_layer_name, result)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Create a mock layer from ingested DEM layers.')
    parser.add_argument('--input_layers', metavar='INPUT LAYERS', nargs='+',
                        required=True,
                        help='List of input layers to create mock layers from')
    parser.add_argument('--output_layers', metavar='OUTPUT LAYERS', nargs='+',
                        required=True,
                        help='List of output layers to create mock layers for (must be same number as input layers')
    parser.add_argument('--num_partitions', metavar="NUM PARTITIONS", type=int,
                        default=None,
                        help='number of partitions to use')
    parser.add_argument('catalog_path', metavar='CATALOG PATH',
                        help='Path to the GeoTrellis Catalog')

    args = parser.parse_args()

    conf = gps.geopyspark_conf(appName="create-additional-layers")
    sc = SparkContext(conf=conf)
    sc.setLogLevel("ERROR")

    #Environments starts with context objects
    print("Spark objects")
    print(sc)

    try:
        create_mock_layers(args.catalog_path,
                           args.input_layers,
                           args.output_layers,
                           args.num_partitions)
    finally:
        sc.stop()
