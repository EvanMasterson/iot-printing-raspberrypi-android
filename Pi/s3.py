import boto3, botocore
from configparser import ConfigParser
from threading import Thread
import os.path

# Create ConfigParse object for reading in values from config.ini file
parser = ConfigParser()
parser.read('keys/config.ini')

# Assign local variables from aws config file
REGION_NAME = parser.get('AWS', 'REGION_NAME')
AWS_ACCESS_KEY_ID = parser.get('AWS', 'AWS_ACCESS_KEY_ID')
AWS_SECRET_ACESS_KEY = parser.get('AWS', 'AWS_SECRET_ACCESS_KEY')
BUCKET_NAME = parser.get('AWS', 'BUCKET_NAME')

# S3 code reference: https://boto3.readthedocs.io/en/latest/index.html
# Responsible for downloading file, once downloaded to tell printer.py to print the file
# Auto creates client when called from listener.py, download method is activated
class S3(Thread):
    def __init__(self):
        self.s3_client = self.connect()
        Thread.__init__(self)

    @staticmethod
    def connect():
        s3_client = boto3.resource('s3',
                                   region_name=REGION_NAME,
                                   aws_access_key_id=AWS_ACCESS_KEY_ID,
                                   aws_secret_access_key=AWS_SECRET_ACESS_KEY)
        return s3_client

    def download(self, filename, client):
        Thread.__init__(self)
        try:
            print "Filename: "+filename
            path = 'downloads/'+filename
            self.s3_client.Bucket(BUCKET_NAME).download_file('uploads/'+filename, path)
            if os.path.isfile(path):
                from printer import Printer
                Printer.print_file(path)
                client.publish('iotprint/response', 'Download Complete: %s' % filename, 1)

        except botocore.exceptions.ClientError as e:
            if e.response['Error']['Code'] == "404":
                client.publish('iotprint/response,' 'Object does not exist, unable to download', 1)
                print("The object does not exist.")
            else:
                raise
