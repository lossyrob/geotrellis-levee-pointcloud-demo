# -*- mode: ruby -*-
# vi: set ft=ruby :

Vagrant.configure(2) do |config|
  config.vm.box = "ubuntu/trusty64"

  config.vm.provider :virtualbox do |vb|
    vb.memory = 8192
    vb.cpus = 6
  end

  config.vm.network :forwarded_port, guest: 8000, host: 8000
  config.vm.network :forwarded_port, guest: 8080, host: 8080
  config.vm.network :forwarded_port, guest: 4040, host: 4040

  # Change working directory to /vagrant upon session start.
  config.vm.provision "shell", inline: <<SCRIPT
    if ! grep -q "cd /vagrant" "/home/vagrant/.bashrc"; then
      echo "cd /vagrant" >> "/home/vagrant/.bashrc"
    fi

    ## Create required directories

    sudo apt-get install -y unzip
    if [ ! -d "/working/data" ]; then
       sudo mkdir -p /working/data && sudo chown -R vagrant:vagrant /working
    fi
    if ! grep -q "export WORK=/working/data" "/home/vagrant/.bashrc"; then
      echo "export WORK=/working/data" >> "/home/vagrant/.bashrc"
    fi

    if [ ! -d "/working/run" ]; then
       sudo mkdir /working/run && sudo chown -R vagrant:vagrant /working
    fi
    if ! grep -q "export RUN=/working/run" "/home/vagrant/.bashrc"; then
      echo "export RUN=/working/run" >> "/home/vagrant/.bashrc"
    fi

    if [ ! -d "/working/result" ]; then
       sudo mkdir /working/result && sudo chown -R vagrant:vagrant /working
    fi
    if ! grep -q "export RESULT=/working/result" "/home/vagrant/.bashrc"; then
      echo "export RESULT=/working/result" >> "/home/vagrant/.bashrc"
    fi
SCRIPT

end
