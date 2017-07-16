#!/bin/bash
mkdir tmp

pushd tmp

curl -o chip1.zip ftp://rockyftp.cr.usgs.gov/vdelivery/Datasets/Staged/Elevation/LPC/Projects/USGS_LPC_LA_Barataria_2013_LAS_2015/las/tiled/USGS_LPC_LA_Barataria_2013_15RYN6548_LAS_2015.zip

unzip chip1.zip
mv *.las ../data

popd

rm tmp/*

pushd tmp

curl -o chip2.zip ftp://rockyftp.cr.usgs.gov/vdelivery/Datasets/Staged/Elevation/LPC/Projects/USGS_LPC_LA_Barataria_2013_LAS_2015/las/tiled/USGS_LPC_LA_Barataria_2013_15RYN6748_LAS_2015.zip

unzip chip2.zip
mv *.las ../data

popd

rm -r tmp
