#!/bin/bash

target=$1

if [ -z $target ] ; then
	target="tests"
fi

hubert='hubert.solita.fi'

remote=$hubert

pybot -d target --exclude integration --exclude fail --RunEmptySuite --variable BROWSER:chrome --variable SELENIUM:http://$remote:4444/wd/hub --variable SERVER:https://www-dev.lupapiste.fi $target
