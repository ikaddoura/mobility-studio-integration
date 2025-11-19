# MobilityStudio Integration

<img src="data/studio_app.png" width="120" alt="MobilityStudio Logo">


A slightly modified version of the matsim-example-project which uses MATSim as library.
This project extends MATSim's default GUI by a config editor.

The idea is to use this project to build a "fat" executable JAR file which can be embeded in the MobilityStudio application. This allows to open the config editor and to start MATSim runs from within MobilityStudio.

**🌐 Website:** [www.mobilitystudio.de](https://www.mobilitystudio.de)

https://github.com/user-attachments/assets/79ad8f19-e9b0-4a0c-9f58-2f05e3e5b668

<img src="data/screenshot_MobilityStudio.png" width="600" alt="MobilityStudio Screenshot">

### Building and Running it locally

You can build an executable jar-file by executing the following command:

```sh
./mvnw clean package
```

or on Windows:

```sh
mvnw.cmd clean package
```

This will download all necessary dependencies (it might take a while the first time it is run) and create a file `mobility-studio-integration-1.0.0.jar` in the top directory. This jar-file can either be double-clicked to start the MATSim GUI, or executed with Java on the command line:

```sh
java -jar mobility-studio-integration-1.0.0.jar
```


### Licenses

The **MATSim program code** in this repository is distributed under the terms of the [GNU General Public License as published by the Free Software Foundation (version 2)](https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html). The MATSim program code are files that reside in the `src` directory hierarchy and typically end with `*.java`.



