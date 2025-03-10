#!/bin/bash

# Common functions and variables for Gosling benchmark scripts

# Input field center coordinates
INPUT_X=640
INPUT_Y=2460

# Submit button center coordinates
SUBMIT_X=640
SUBMIT_Y=2664

# Function to escape spaces in a string
escape_spaces() {
    # Replace each space with a backslash followed by a space
    echo "${1// /\\ }"
}

# Function to click on the input field and enter text
input_text() {
    local message="$1"
    
    # Click on input field
    echo "Clicking input field..."
    adb shell input tap $INPUT_X $INPUT_Y
    sleep 1

    # Type text - escape spaces in the message
    local escaped_message=$(escape_spaces "$message")
    echo "Typing text: $message"
    adb shell input text "$escaped_message"
    sleep 1
}

# Function to click the submit button and record start time
click_submit() {
    echo "Clicking submit button..."
    START_TIME=$(date +%s.%N)
    adb shell input tap $SUBMIT_X $SUBMIT_Y
    # Make START_TIME available to the calling script
    export START_TIME
}

# Function to click input and submit in one step
input_and_submit() {
    local message="$1"
    input_text "$message"
    click_submit
}

# Function to calculate time difference from START_TIME
get_elapsed_time() {
    local end_time=$(date +%s.%N)
    local time_diff=$(echo "$end_time - $START_TIME" | bc)
    echo "$time_diff"
}

#echo "Dumping UI hierarchy..."
#adb shell uiautomator dump
#adb pull /sdcard/window_dump.xml
