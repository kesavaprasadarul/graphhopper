FROM openjdk:17-jdk-slim
LABEL authors="kesav"

# Set working directory
WORKDIR /app

# Download necessary files
RUN apt-get update && apt-get install -y wget && \
    wget https://repo1.maven.org/maven2/com/graphhopper/graphhopper-web/10.0/graphhopper-web-10.0.jar && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Set environment variables for configuration and map file
ENV CONFIG_FILE=config-example.yml
ENV MAP_FILE=berlin-latest.osm.pbf

# Expose the required port
EXPOSE 8989

# Run GraphHopper server
CMD ["java", "-Ddw.graphhopper.datareader.file=${MAP_FILE}", "-jar", "graphhopper-web-10.0.jar", "server", "${CONFIG_FILE}"]
