// Copyright (c) 2013 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

var exec = require('cordova/exec');
var platform = require('cordova/platform');

var STATE_ACTIVE = 'active';
var STATE_IDLE = 'idle';
var STATE_LOCKED = 'locked';

// This keeps track of the current state.
var currentState = STATE_ACTIVE;

// These listeners are called when the device's state changes.
var stateListeners = [ ];

// This interval represents the amount of idle time required for the above state listeners to fire with status "idle".
var detectionIntervalInSeconds = 60;

// This is a timeout used to trigger the idle state.
var idleTimer = null;

// This tracks the last time a touch event occurred.
var lastInputDate = new Date();

exports.queryState = function(_detectionIntervalInSeconds, callback) {
    // If the device is locked, this is easy.
    if (currentState === STATE_LOCKED) {
        callback(STATE_LOCKED);
        return;
    }

    // We can't simply return the current state.
    // This is because the specified detection interval may be different than the one used to determine the current state.
    var currentDate = new Date();
    msSinceLastInput = currentDate.getTime() - lastInputDate.getTime();
    if (msSinceLastInput >= _detectionIntervalInSeconds * 1000) {
        callback(STATE_IDLE);
    } else {
        callback(STATE_ACTIVE);
    }
};

exports.setDetectionInterval = function(_detectionIntervalInSeconds) {
    detectionIntervalInSeconds = _detectionIntervalInSeconds;
    resetIdleTimer();
};

exports.onStateChanged = { };
exports.onStateChanged.addListener = function(listener) {
    if (typeof(listener) === 'function') {
        stateListeners.push(listener);
    } else {
        console.log('Attempted to add a non-function listener.');
    }
}

// This function fires the state listeners with the given state.
var fireListeners = function(state) {
    for (var i = 0; i < stateListeners.length; i++) {
        stateListeners[i](state);
    }
};

// This function resets the idle timer.
var resetIdleTimer = function() {
    clearTimeout(idleTimer);
    idleTimer = setTimeout(changeState, detectionIntervalInSeconds * 1000, STATE_IDLE);
};

// This function handles a change in state.
var changeState = function(state) {
    // If we have a new state, set it appropriately and fire the state listeners.
    if (currentState !== state) {
        currentState = state;
        fireListeners(state);
    }
}

// This function handles a touch event by resetting the idle timer and changing the state.
var handleTouchEvent = function() {
    lastInputDate = new Date();
    resetIdleTimer();
    changeState(STATE_ACTIVE);
}

// Start the idle timer.
resetIdleTimer();

// Add a touch listener.
document.addEventListener('touchstart', handleTouchEvent);

// If we're on Android, add a listener for screen locking.
if (platform.id === 'android') {
    var lockCallback = function(newState) {
        // If the device has been unlocked, there must have been a touch event, so we handle that.
        // If the device has been locked, we merely want to change the state to reflect this.
        if (newState === STATE_ACTIVE) {
            handleTouchEvent();
        } else if (newState === STATE_LOCKED) {
            changeState(STATE_LOCKED);
        }
    }
    exec(lockCallback, undefined, 'ChromeIdle', 'initialize', []);
}

