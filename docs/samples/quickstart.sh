#!/bin/bash

# This script takes as arguments
# 1) A tomcat distribution.
# 
# It then unpacks the tomcat distribution in the current folder and deploys the four WAR files onto this tomcat instance
# The server.xml is overwritten with a default server.xml
# A log4j.properties with DEBUG logging is copied into the tomcat lib folder. 
# Finally, the tomcat instance is started using catalina run
#
# If all goes well, we should be able to point the browser to tomcat instance and then see the archiver homescreen.
#
# This needs to be a UNIX file.

DEFAULT_LOG_LEVEL=ERROR
START_IN_BACKGROUND=FALSE

while getopts ":vs" opt; do
  case $opt in
    v)
      echo "Setting log levels to DEBUG." >&2
      DEFAULT_LOG_LEVEL=DEBUG
      ;;
    s)
      echo "Starting in the background" >&2
      START_IN_BACKGROUND=TRUE
      ;;
    \?)
      echo "Invalid option: -$OPTARG" >&2
      exit 1
      ;;
  esac
done

shift $(($OPTIND - 1))

if [[ $# < 1 ]] 
then
  echo "Usage: $0 <Tomcat Distribution>"
  exit 1
fi

TOMCAT_DISTRIBUTION=$1

if [[ ! -f ${TOMCAT_DISTRIBUTION} ]]
then
  echo "${TOMCAT_DISTRIBUTION} is not a valid file?"
  exit 1
fi

FQ_HOSTNAME=`hostname -f`

if [[ -z ${FQ_HOSTNAME} ]]
then
  echo "Unknown/empty hostname. Setting hostname to localhost"
  FQ_HOSTNAME="localhost"
fi


# Create an appliances.xml file and set up this appliance's identity.
cat > appliances.xml <<EOF
 <appliances>
   <appliance>
     <identity>appliance0</identity>
     <cluster_inetport>localhost:16670</cluster_inetport>
     <mgmt_url>http://localhost:17665/mgmt/bpl</mgmt_url>
     <engine_url>http://localhost:17665/engine/bpl</engine_url>
     <etl_url>http://localhost:17665/etl/bpl</etl_url>
     <retrieval_url>http://localhost:17665/retrieval/bpl</retrieval_url>
     <data_retrieval_url>http://${FQ_HOSTNAME}:17665/retrieval</data_retrieval_url>
   </appliance>
 </appliances>
EOF
 
export ARCHAPPL_APPLIANCES=${PWD}/appliances.xml
export ARCHAPPL_MYIDENTITY=appliance0

if [[ ! -f ${ARCHAPPL_APPLIANCES} ]]
then
	echo "We just generated the appliances.xml file but we cannot seem to find it here ${ARCHAPPL_APPLIANCES}"
	echo "The archiver appliance will not start successfully without this file"
	exit 1
fi


# Set up the storage folders based on the default policy..
export ARCHAPPL_SHORT_TERM_FOLDER=/dev/shm/quickstart_sts

export ARCHAPPL_MEDIUM_TERM_FOLDER=${PWD}/MTS
if [[ ! -d ${ARCHAPPL_MEDIUM_TERM_FOLDER} ]]
then
	mkdir ${ARCHAPPL_MEDIUM_TERM_FOLDER}
fi

export ARCHAPPL_LONG_TERM_FOLDER=${PWD}/MTS
if [[ ! -d ${ARCHAPPL_LONG_TERM_FOLDER} ]]
then
	mkdir ${ARCHAPPL_LONG_TERM_FOLDER}
fi

# Use an in memory persistence layer, if one is not defined in the environment
if [ -z ${ARCHAPPL_PERSISTENCE_LAYER} ] 
then
	export ARCHAPPL_PERSISTENCE_LAYER=org.epics.archiverappliance.config.persistence.InMemoryPersistence
fi

echo "Using ${ARCHAPPL_PERSISTENCE_LAYER} as the persistence layer"

if [[ -d quickstart_tomcat ]]
then
    echo "Found an older quickstart_tomcat folder. Stopping any existing instances."
    pushd quickstart_tomcat
    for tomcatfolder in apache-tomcat*; do ${tomcatfolder}/bin/catalina.sh stop; done
    sleep 30
    popd
	echo "Deleting an older quickstart_tomcat"
	rm -rf quickstart_tomcat
fi

mkdir quickstart_tomcat
tar -C quickstart_tomcat -zxf  ${TOMCAT_DISTRIBUTION}
pushd quickstart_tomcat
TOMCAT_VERSION_FOLDER=`ls -d apache-tomca* | head -1`

# Make sure we have a valid server.xml
if [[ ! -f ${TOMCAT_VERSION_FOLDER}/conf/server.xml ]]
then
  echo "After expanding the tomcat distribution, ${TOMCAT_DISTRIBUTION} into quickstart_tomcat/${TOMCAT_VERSION_FOLDER}, we cannot find ${TOMCAT_VERSION_FOLDER}/server.xml as expected."
  exit 1
fi

# Write a minimal server.xml into unpacked tomcat distribution.
cat > ${TOMCAT_VERSION_FOLDER}/conf/server.xml <<EOF
<?xml version='1.0' encoding='utf-8'?>
<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->
<Server port="16000" shutdown="SHUTDOWN">
  <Listener className="org.apache.catalina.core.AprLifecycleListener" SSLEngine="on" />
  <Listener className="org.apache.catalina.core.JasperListener" />
  <Listener className="org.apache.catalina.core.JreMemoryLeakPreventionListener" />
  <Listener className="org.apache.catalina.mbeans.GlobalResourcesLifecycleListener" />
  <Listener className="org.apache.catalina.core.ThreadLocalLeakPreventionListener" />

  <Service name="Catalina">
    <Connector port="17665" protocol="HTTP/1.1" connectionTimeout="20000" redirectPort="8443" />
    <Engine name="Catalina" defaultHost="localhost">
      <Host name="localhost"  appBase="webapps" unpackWARs="true" autoDeploy="true">
        <Valve className="org.apache.catalina.valves.AccessLogValve" directory="logs" prefix="localhost_access_log." suffix=".txt" pattern="%h %l %u %t &quot;%r&quot; %s %b %D" />
      </Host>
    </Engine>
  </Service>
</Server>
EOF

# Write a log4.properties file into the lib folder
cat > ${TOMCAT_VERSION_FOLDER}/lib/log4j.properties <<EOF
log4j.rootLogger=${DEFAULT_LOG_LEVEL}, console
log4j.logger.org.apache.http=ERROR
log4j.logger.config.org.epics.archiverappliance=DEBUG

log4j.appender.console=org.apache.log4j.ConsoleAppender
log4j.appender.console.layout=org.apache.log4j.PatternLayout
log4j.appender.console.layout.ConversionPattern=%-4r [%t] %-5p %c %x - %m%n
EOF

# Now, deploy the WAR files. 
cp ../mgmt.war ${TOMCAT_VERSION_FOLDER}/webapps
cp ../engine.war ${TOMCAT_VERSION_FOLDER}/webapps
cp ../etl.war ${TOMCAT_VERSION_FOLDER}/webapps
cp ../retrieval.war ${TOMCAT_VERSION_FOLDER}/webapps

# Unpack the mgmt war so that we can replace any images etc..
pushd ${TOMCAT_VERSION_FOLDER}/webapps
if [[ -d mgmt ]]
then
  rm -rf mgmt
fi
mkdir mgmt
cd mgmt
jar xf ../mgmt.war
popd

pwd
ls -ltr 

if [[ -f ../site_specific_content/template_changes.html ]]
then
  echo "Modifying static content to cater to site specific information"
  java -cp ${TOMCAT_VERSION_FOLDER}/webapps/mgmt/WEB-INF/classes org.epics.archiverappliance.mgmt.bpl.SyncStaticContentHeadersFooters ../site_specific_content/template_changes.html ${TOMCAT_VERSION_FOLDER}/webapps/mgmt/ui
fi

if [[ -d ../site_specific_content/img ]]
then
  echo "Replacing site specific images"
  cp -R ../site_specific_content/img/* ${TOMCAT_VERSION_FOLDER}/webapps/mgmt/ui/comm/img/
fi


# Start up the JVM with 1Gb of heap...
export JAVA_OPTS="-XX:MaxPermSize=128M -Xmx1G -ea"

ARCH=`uname -m`
if [[ $ARCH == 'x86_64' || $ARCH == 'amd64' ]]
then
  echo "Using 64 bit versions of libraries"
  export LD_LIBRARY_PATH=${TOMCAT_VERSION_FOLDER}/webapps/engine/WEB-INF/lib/native/linux-x86_64:${LD_LIBRARY_PATH}
else
  echo "Using 32 bit versions of libraries"
  export LD_LIBRARY_PATH=${TOMCAT_VERSION_FOLDER}/webapps/engine/WEB-INF/lib/native/linux-x86:${LD_LIBRARY_PATH}
fi

# Start tomcat up...
if [[ $START_IN_BACKGROUND == "TRUE" ]]
then
  echo "Starting in the background..." 
  ${TOMCAT_VERSION_FOLDER}/bin/catalina.sh start
  sleep 5
else
  echo "Starting in the foreground..." 
  ${TOMCAT_VERSION_FOLDER}/bin/catalina.sh run
fi

popd