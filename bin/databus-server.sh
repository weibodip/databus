#!/bin/bash

# Created by jianhong1 on 2019-08-27.

# chkconfig: 2345 57 27

JAVA_HOME="/usr/local/jdk1.8.0_144"
JAVA_OPTS="-Xms512M -Xmx1G"
JAVA_JMX="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.port=8999"
CLASSPATH="/data0/workspace/databus/conf/:/data0/workspace/databus/target/databus-1.0.0.jar:/data0/workspace/databus/target/lib/*"
APP_MAINCLASS="com.weibo.dip.databus.DatabusDriver"
STOP_FILE="/data0/workspace/databus/pipelines/stop.file"

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
#    cmd="nohup $JAVA_HOME/bin/java $JAVA_OPTS $JAVA_JMX -cp $CLASSPATH $APP_MAINCLASS > /dev/null 2>&1 &"
    cmd="nohup $JAVA_HOME/bin/java $JAVA_OPTS -cp $CLASSPATH $APP_MAINCLASS > /dev/null 2>&1 &"
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
    n=20
    sum=0
    sleep_time=1
    while [[ $output != "" && $sum -lt `expr $n \* $sleep_time` ]]
    do
        sleep $sleep_time
        sum=`expr $sleep_time + $sum`
        echo "databus stopping, elapsed $sum seconds"
        output=`ps aux | grep com.weibo.dip.databus.DatabusDriver | grep -v grep`
    done
    if [[ $output = "" ]]
    then
        echo "databus stopped"
        sleep 2
        start
    else
        echo "restart timeout!"
    fi
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
