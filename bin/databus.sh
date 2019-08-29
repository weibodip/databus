#!/bin/bash

# Created by jianhong1 on 2019-08-27.

JAVA_HOME="/usr/local/dip/jdk1.8.0_144"
JAVA_OPTS="-Xms512M -Xmx1G"
JAVA_JMX="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.port=8999"
CLASSPATH="/usr/local/dip/databus/edge-conf/:/usr/local/dip/databus/target/databus-1.0.0.jar:/usr/local/dip/databus/target/lib/*"
APP_MAINCLASS="com.weibo.dip.databus.DatabusDriver"
STOP_FILE="/usr/local/dip/databus/pipelines/stop.file"

output=`ps aux | grep com.weibo.dip.databus.DatabusDriver | grep -v grep`

start(){
    if [[ $output != "" ]]
    then
        echo "databus already running"
        return
    fi
    prepare="rm -f $STOP_FILE"
    echo $prepare
    echo `$prepare`
    echo "starting databus:"
    cmd="nohup $JAVA_HOME/bin/java $JAVA_OPTS $JAVA_JMX -cp $CLASSPATH $APP_MAINCLASS > /dev/null 2>&1 &"
    echo $cmd
    echo `su -c "$cmd"`
}

stop(){
    if [[ $output = "" ]]
    then
        echo "databus already stopped"
        return
    fi
    echo "add stop.file, databus stopping:"
    cmd="touch $STOP_FILE"
    echo $cmd
    echo `$cmd`
}

restart(){
    stop
    sleep 10
    start
}

status(){
    if [[ $output = "" ]]
    then
        echo "databus stopped"
    else
        echo "databus running"
    fi
}

case "$1" in
    start)
        start
    ;;
    stop)
        stop
    ;;
    restart)
        restart
    ;;
    status)
        status
    ;;
    *)
        echo "Usage: $0 {start|stop|restart|status}"
        exit 1
esac