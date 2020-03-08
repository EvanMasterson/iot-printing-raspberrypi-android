from AWSIoTPythonSDK.MQTTLib import AWSIoTMQTTClient
from printer import Printer
from configparser import ConfigParser
import json, time

# Create ConfigParse object for reading in values from config.ini file
parser = ConfigParser()
parser.read('keys/config.ini')

# Assign local variables from aws config file
host = parser.get('AWS', 'HOST')
rootCAPath = parser.get('AWS', 'ROOT_CA_PATH')
certificatePath = parser.get('AWS', 'CERTIFICATE_PATH')
privateKeyPath = parser.get('AWS', 'PRIVATE_KEY_PATH')

# Init AWSIoTMQTTClient
myAWSIoTMQTTClient = AWSIoTMQTTClient("iotprinting")
myAWSIoTMQTTClient.configureEndpoint(host, 8883)
myAWSIoTMQTTClient.configureCredentials(rootCAPath, privateKeyPath, certificatePath)

# AWSIoTMQTTClient connection configuration
myAWSIoTMQTTClient.configureAutoReconnectBackoffTime(1, 32, 20)
myAWSIoTMQTTClient.configureOfflinePublishQueueing(-1)  # Infinite offline Publish queueing
myAWSIoTMQTTClient.configureDrainingFrequency(2)  # Draining: 2 Hz
myAWSIoTMQTTClient.configureConnectDisconnectTimeout(10)  # 10 sec
myAWSIoTMQTTClient.configureMQTTOperationTimeout(5)  # 5 sec

filename = ''
active = False

def callback(client, userdata, message):
    global filename, printer_name, active
    print('Received new message:')
    print(message.payload)
    print('from topic:')
    print(message.topic)
    print('---------\n')
    try:
        content = json.loads(message.payload)
        filename = content.get('filename', '')
        active = content.get('print', False)
    except ValueError as e:
        print(e, 'invalid json')

    printer.process(filename, active)

# Connect and subscribe to AWS IoT
myAWSIoTMQTTClient.connect()

printer = Printer(filename, active, myAWSIoTMQTTClient)

myAWSIoTMQTTClient.subscribe('iotprint/message', 1, callback)

while True:
    time.sleep(1)