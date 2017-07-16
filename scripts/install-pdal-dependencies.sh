sudo apt-get install -y git
sudo apt-get install -y cmake
sudo apt-get install -y g++
sudo apt-get install -y libgdal-dev libgeotiff-dev

# Install Java.
sudo echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | sudo debconf-set-selections && \
    sudo add-apt-repository -y ppa:webupd8team/java && \
    sudo apt-get update && \
    sudo apt-get install -y oracle-java8-installer && \
    sudo rm -rf /var/lib/apt/lists/* && \
    sudo rm -rf /var/cache/oracle-jdk8-installer

export JAVA_HOME=/usr/lib/jvm/java-8-oracle
