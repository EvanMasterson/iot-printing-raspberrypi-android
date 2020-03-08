from listener import Listener
from buzzer import Buzzer
from threading import Thread
import os

# Responsible for ultimately printing the file
# Makes a call to the Listener thread with a filename if printing was set to true/false and the mqtt client
class Printer(Listener, Thread):
    def __init__(self, filename, active, client):
        super(Printer, self).__init__(filename, active, client)
        Thread.__init__(self)

    @staticmethod
    def print_file(filename):
        print "printing"
        os.system("lpr %s" % filename)
        buzzer = Buzzer(True)
        buzzer.buzz()
