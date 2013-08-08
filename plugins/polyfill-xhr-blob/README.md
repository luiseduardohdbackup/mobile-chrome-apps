xhr-blob
========

A Cordova plugin which provides Blob return type support for XHMHttpRequest in
mobile Safari.

##Overview

This plugin adds support for a return type of 'blob' for browsers which do not
support it. If an XMLHttpRequest's returnType property is set to 'blob', it
will be set internally to 'arraybuffer', and a blob will be constructed when
the response is examined.

##Installation

### Cordova 2.7 or later with cordova cli and Plugman

*   Assuming you've used cordova create to create the platforms, you can use
        cordova plugin add directory-of-the-xhr-blob-plugin
    to add the plugin

##Usage

    var xhr = new XMLHttpRequest();
    xhr.open('GET', 'http://www.example.com/path/to/resource');
    xhr.responseType = 'blob';
    xhr.onreadystatechange = function() {
        if (xhr.readyState === 4) {
            var url = window.webkitURL.createObjectURL(xhr.response);
            alert(url);
        }
    };
    xhr.send();