download-data:
	@./scripts/download-data.sh

copy-data:
	@cp -r data/* $(WORK)

build-pdal:
	sudo apt-get update
	sudo scripts/install-pdal-dependencies.sh
	sudo apt-get install -y git
	@if [ ! -d "pdal" ]; then git clone https://github.com/pdal/pdal; fi
	@if [ ! -d "pdal/makefiles" ]; then mkdir pdal/makefiles; fi
	@export JAVA_HOME=/usr/lib/jvm/java-8-oracle && \
		cd pdal/makefiles && \
	 	../../scripts/pdal-config.sh && \
	 	$(MAKE) -j 2 && \
	 	sudo $(MAKE) install
	@mkdir vdatum \
	     && cd vdatum \
	     && wget http://download.osgeo.org/proj/vdatum/usa_geoid2012.zip && sudo unzip -j -u usa_geoid2012.zip -d /usr/share/proj \
	     && wget http://download.osgeo.org/proj/vdatum/usa_geoid2009.zip && sudo unzip -j -u usa_geoid2009.zip -d /usr/share/proj \
	     && wget http://download.osgeo.org/proj/vdatum/usa_geoid2003.zip && sudo unzip -j -u usa_geoid2003.zip -d /usr/share/proj \
	     && wget http://download.osgeo.org/proj/vdatum/usa_geoid1999.zip && sudo unzip -j -u usa_geoid1999.zip -d /usr/share/proj \
	     && wget http://download.osgeo.org/proj/vdatum/vertcon/vertconc.gtx && sudo mv vertconc.gtx /usr/share/proj \
	     && wget http://download.osgeo.org/proj/vdatum/vertcon/vertcone.gtx && sudo mv vertcone.gtx /usr/share/proj \
	     && wget http://download.osgeo.org/proj/vdatum/vertcon/vertconw.gtx && sudo mv vertconw.gtx /usr/share/proj \
	     && wget http://download.osgeo.org/proj/vdatum/egm96_15/egm96_15.gtx && sudo mv egm96_15.gtx /usr/share/proj \
	     && wget http://download.osgeo.org/proj/vdatum/egm08_25/egm08_25.gtx && sudo mv egm08_25.gtx /usr/share/proj
	@rm -rf vdatum

build-geotrellis:
	@if [ ! -d "geotrellis" ]; then git clone https://github.com/locationtech/geotrellis; fi
	@cd geotrellis && \
		git checkout milestone/pointcloud && \
		./sbt "project util" publish-local && \
		./sbt "project macros" publish-local && \
		./sbt "project pointcloud" publish-local && \
		./sbt "project proj4" publish-local && \
		./sbt "project vector" publish-local && \
		./sbt "project raster" publish-local && \
		./sbt "project spark" publish-local && \
		./sbt "project spark-etl" publish-local && \
		./sbt "project s3" publish-local && \
		./sbt "project vectortile" publish-local && \
		./sbt "project s3-testkit" publish-local

get-spark:
	@wget https://d3kbcqa49mib13.cloudfront.net/spark-2.2.0-bin-hadoop2.7.tgz
	@tar -xzf spark-2.2.0-bin-hadoop2.7.tgz

install-geopyspark: export SPARK_HOME=spark-2.2.0-bin-hadoop2.7/
install-geopyspark:
	sudo apt-get install -y python3-pip
	pip3 install --user geopyspark==0.2.0rc2
	geopyspark install-jar

build-project:
	@./sbt assembly

copy-code:
	@echo "CURDIR: $(CURDIR)"
	cp target/scala-2.11/levee-pointcloud-demo.jar $(RUN)
	cp src/main/python/* $(RUN)

count-points:
	scripts/run-count-points.pbs

create-viewshed-viz:
	scripts/run-create-viz-layers.pbs levee-viewshed levee-viewshed-viz

create-dem-viz:
	scripts/run-create-viz-layers.pbs levee-dem levee-dem-viz

create-mock-viz:
	scripts/run-create-viz-layers.pbs mock-dem mock-dem-viz

mock-dem:
	scripts/run-create-mock-layers.pbs

ingest-dem:
	scripts/run-ingest-dem.pbs

compute-viewshed:
	spark-2.2.0-bin-hadoop2.7/bin/spark-submit \
		--conf 'spark.driver.memory=5g' \
		--conf 'spark.driver.extraJavaOptions=-Djava.library.path=/usr/local/lib' \
		--conf 'spark.executor.extraJavaOptions=-Djava.library.path=/usr/local/lib' \
		--class com.azavea.demo.ComputeViewshed \
		target/scala-2.11/levee-pointcloud-demo.jar

serve-tiles:
	./sbt "run-main com.azavea.demo.ServeTiles"

serve-static:
	@cd static && python -m SimpleHTTPServer 8000
