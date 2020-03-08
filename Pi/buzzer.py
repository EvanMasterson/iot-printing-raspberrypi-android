from threading import Thread
import time
from grovepi import *

buzzer_pin = 2		# Port for buzzer
pinMode(buzzer_pin, "OUTPUT")    # Assign mode for buzzer as output

# Responsible for some buzzer feedback when a file is printing
# Activated by printer.py in the print_file method
class Buzzer(Thread):
    def __init__(self, buzzer_state=False):
        self.buzzer_state = buzzer_state
        Thread.__init__(self)

    def buzz(self):
        Thread.__init__(self)
        while self.buzzer_state:
            try:
                digitalWrite(buzzer_pin, 1)
                time.sleep(0.3)
                digitalWrite(buzzer_pin, 0)
                break
            except KeyboardInterrupt:	# Stop the buzzer before stopping
                digitalWrite(buzzer_pin, 0)
                break
            except (IOError,TypeError) as e:
                print(e, "Error")
