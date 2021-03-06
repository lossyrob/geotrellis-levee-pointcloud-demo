## TO BE RUN IN THE VM

CONFIG="Unix Makefiles"

if ! [ -z "$1" ]; then
    CONFIG="$1"
fi

CC=$CC CXX=$CXX cmake -G "$CONFIG" .. \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX=/usr/local \
        -DBUILD_PLUGIN_OCI=OFF \
        -DBUILD_PLUGIN_SQLITE=OFF \
        -DBUILD_PLUGIN_PGPOINTCLOUD=OFF \
        -DBUILD_OCI_TESTS=OFF \
        -DBUILD_PLUGIN_HEXBIN=OFF \
        -DBUILD_PLUGIN_NITF=OFF \
        -DBUILD_PLUGIN_PYTHON=OFF \
        -DBUILD_PLUGIN_MRSID=OFF \
        -DBUILD_PLUGIN_MBIO=OFF \
        -DBUILD_PLUGIN_CPD=OFF \
        -DBUILD_PLUGIN_ICEBRIDGE=OFF \
        -DBUILD_PLUGIN_PCL=OFF \
        -DBUILD_PLUGIN_MATLAB=OFF \
        -DBUILD_PLUGIN_GREYHOUND=OFF \
        -DWITH_LAZPERF=OFF \
        -DWITH_TESTS=OFF \
        -DWITH_PDAL_JNI=ON
