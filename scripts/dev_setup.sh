#!/bin/bash

install_macos_dependencies() {
  brew install openjdk@21
  export JAVA_HOME="$(brew --prefix)/opt/openjdk@21"
  brew install node
  brew install tesseract
  brew install ffmpeg
  brew install mediainfo
}

install_ubuntu_debian_dependencies() {
  sudo apt update
  sudo apt install -y openjdk-21-jdk nodejs npm tesseract-ocr ffmpeg mediainfo
}

install_fedora_dependencies() {
  sudo dnf install -y java-21-openjdk-devel nodejs npm tesseract ffmpeg mediainfo
}

echo "Installing dependencies..."
if [[ "$OSTYPE" == "darwin"* ]]; then
  install_macos_dependencies
elif [[ -f /etc/debian_version ]]; then
  install_ubuntu_debian_dependencies
elif [[ -f /etc/fedora-release ]]; then
  install_fedora_dependencies
else
  echo "Unsupported OS. Feel free to contribute!"
  exit 1
fi

echo "Dependencies installed."

cd "$(dirname "$0")/.." || exit 1

echo "Installing frontend dependencies..."
cd docs-web/src/main/webapp && npm install && cd ../../../..

echo "Building backend..."
./mvnw clean -DskipTests install

echo ""
echo "Setup complete. To start the dev server:"
echo "  cd docs-web && ../mvnw jetty:run"
echo "  cd docs-web/src/main/webapp && npm run dev"
echo ""
echo "Access at http://localhost:8080"
