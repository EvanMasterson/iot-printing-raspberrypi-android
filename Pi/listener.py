from threading import Thread
from s3 import S3

# printer.py file inherits this Listener
# File used to listen for any message coming in, called in the aws_connect.py file
# by printer.process
# Activates process to download the file from S3
class Listener(Thread):
    def __init__(self, filename, active, client):
        self.filename = filename
        self.active = active
        self.client = client
        Thread.__init__(self)

    def process(self, filename, active):
        self.filename = filename
        self.active = active
        if self.active:
            print 'printing is true'
            if not self.isAlive():
                self.start()


    def run(self):
        while self.active:
            s3 = S3()
            s3.download(self.filename, self.client)
            break
        Thread.__init__(self)