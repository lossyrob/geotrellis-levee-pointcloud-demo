# GeoTrellis Levee PointCloud Demo

This demo provides a startup guide for kicking the tires of GeoTrellis PointCloud support.

__Note:__ Point Cloud support in GeoTrellis is a feature that is being developed; a good deal of functionality is there, but the project organization and API are not where they need to be.
This means using it in it's current state will have quite the "rough around the edges feel.
We're committed to finishing these features and functionality based on use cases.

## Initializing the development environment

This project can be worked on in a vagrant box, created with the vagrant file provided.
This is to provide the instructions against a clean environment.
To run inside the vagrant box, you'll need [vagrant](https://www.vagrantup.com/) installed, as well as a vagrant provider
such as [VirtualBox](https://www.virtualbox.org/).

To bring up the vagrant box, in this directory:

```
> vagrant up
```

_Note:_ You may want to mess around with the vb.memory and vb.cpu settings, if you are using VirtualBox.

Once the vagrant box is started, ssh inside of it

```
vagrant ssh
```

From there, we'll be interacting with `make` to perform different steps.
Look at the `Makefile` to see what those steps are doing.

## Downloading the data

Run

```
scripts/download_data.sh
```

To download some example data, which is lidar from the USGS of
a levee in Barataria Bay, Louisiana.

## Install PDAL JNI bindings.

We'll need to clone and and build pdal and the java bindings.

```
make build-pdal
```

## Build GeoTrellis with Point Cloud support

```
make build-geotrellis
```

## Build this project into an assembly (uber-jar)

```
make build-project
```

## Check to see that PDAL bindings are working

This will execute the CountPoints.scala code in the project,
counting the points in our test data and proving that everything
is installed correctly.

```
make count-points
```

## Ingest our lidar data into DEM

```
make ingest-dem
```

## Start up a server that will show the DEM on a web map

```
make serve-tiles
```

## Serve out the static content that shows the leaflet map

While `serve-tiles` is still running, also run:

```
make serve-static
```

Navigate to `http://localhost:8000`, and you should see a map with the elevation tiles.

## Compute a viewshed on the ingested DEM
