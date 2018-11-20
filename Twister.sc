DummyMIDI {
	var dummy = 0;

	*new {
		^super.newCopyArgs();
	}

	noteOn {}
	noteOff {}
	control {}
	sysex {}
	cc { ^127 }
	channel {^16}
	latency_{
		dummy = 0;
	}
}

PropertyAnimator {
	classvar fps, animators, finished, routine;

	var obj, prop, fps;
	var getter, setter, start, end, dur, fps, currentTime, endTime;
	var routine;

	*new {
		|obj, prop, fps=15|
		^this.newCopyArgs(obj, prop, fps).init;
	}

	init {
		getter = prop.asSymbol;
		setter = prop.asSymbol.asSetter;
		currentTime = 0;
	}

	set {
		|value, inDur=0|
		dur = inDur;

		if (dur == 0) {
			this.stop();
			this.prSet(value);
		} {
			if (value.isArray) {
				#start, end = value;
			} {
				start = obj.perform(getter);
				end = value;
			};

			this.start();
		}
	}

	prSet {
		|value|
		obj.perform(setter, value);
	}

	start {
		routine.stop();

		routine = Routine({
			| startTime |
			currentTime = startTime;
			endTime = startTime + dur;

			while { currentTime < endTime } {
				obj.perform(setter, currentTime.linlin(startTime, endTime, start, end));
				currentTime = fps.reciprocal.wait;
			};

			obj.perform(setter, end);
		});

		routine.play(AppClock);
	}

	stop {
		routine.stop();
		routine = nil;
	}
}

Twister {
	var <device;
	var <knobs, <buttons;
	var <deviceConnections;

	*new {
		|device|
		^super.new.init.connect(device);
	}

	init {
		knobs = 16.collect {
			TwisterKnob()
		};

		buttons = 6.collect {
			TwisterButton();
		}
	}

	rows {
		|x, y|
		var result = knobs.clump(4);

		if (x.notNil) {
			result = result[x];
			if (y.notNil) {
				result = result[y];
			}
		};

		^result;
	}

	cols {
		|y, x|
		var result = knobs.clump(4);

		if (y.notNil) {
			result = result[y];
			if (x.notNil) {
				result = result[x];
			}
		};

		^result;
	}

	connect {
		|inDevice|
		if (inDevice.isKindOf(Symbol)) {
			inDevice = TwisterDevice(inDevice);
		};

		if (inDevice != device) {
			if (device.notNil) {
				this.disconnect();
			};

			device = inDevice;
			if (device.notNil) {
				device.changed(\modelConnected, this);
				"Connecting TwisterDevice(%) to Twister(%)".format(device.identityHash, this.identityHash).postln;

				knobs.do({
					|knob, i|
					knob.connect(device.knobs[i])
				});

				buttons.do({
					|button, i|
					button.connect(device.buttons[i])
				});

				deviceConnections = ConnectionList [
					device.signal(\deviceConnected).connectTo(this.methodSlot(\onDeviceConnected)),
					device.signal(\modelConnected).connectTo(this.methodSlot("onModelConnected(value)")),
				];
			}

		}
	}

	disconnect {
		if (device.notNil) {
			"Disconnecting TwisterDevice(%) from Twister(%)".format(device.identityHash, this.identityHash).postln;
			device = nil;
			knobs.do(_.disconnect());
		};

		deviceConnections.free.clear;
	}

	updateDevice {
		knobs.do(_.updateDevice())
	}

	onDeviceConnected {
		this.updateDevice();
	}

	onModelConnected {
		|twisterObj|
		if (twisterObj != this) {
			this.disconnect();
		}
	}

	free {
		this.releaseDependants();
		this.disconnect();
		knobs.clear; buttons.clear;
	}

	brightness_{
		|val|
		knobs.do {
			|k|
			k.brightness = val;
		}
	}
}

TwisterButton {
	var <device, <deviceConnections,
	<>toggle = false,
	<cv;

	*new {
		|device|
		^super.new.init().connect(device)
	}

	init {
		this.cv = OnOffControlValue(\off);
	}

	connect {
		|inDevice|
		if (inDevice != device) {
			if (device.notNil) {
				this.disconnect();
			};

			device = inDevice;
			if (inDevice.notNil) {
				// "Connecting device(%) to knob(%)".format(device.identityHash, this.identityHash).postln;
				deviceConnections = ConnectionList [
					device.signal(\button).connectTo(this.methodSlot("onButton(value)")),
				];
			}
		}
	}

	disconnect {
		deviceConnections.free;
	}

	onButton {
		|value|
		cv !? {
			if (toggle) {
				if (value == \on) {
					cv.toggle;
				}
			} {
				cv.value = value
			};
		};

		this.changed(\value, value);
	}

	cv_{
		|inCV|
		cv = inCV;
	}
}


TwisterKnob {
	classvar <>animator;
	classvar <>animations, <>pausedAnimations;

	var <device;
	var <knobConnections, <buttonConnections;
	var <ledColor, <buttonState=\up, enabled=false;
	var <hueAnimator, <brightnessAnimator, <brightness = 0.5, fadeoutCollapse;
	var deviceConnections, knobConnections, buttonConnections;
	var <>toggle = false;
	var <knobCV, <buttonCV;
	var lastInputT=0, input;

	var <>knobScale = 0.005;

	var animTarget, animRate, resumeAnim;

	*new {
		|device|
		^super.new.init().connect(device)
	}

	init {
		animations = animations ?? { IdentitySet() };
		pausedAnimations = animations ?? { IdentitySet() };

		ledColor = Color.blue(0.9);
		this.buttonCV = OnOffControlValue(\off);
		resumeAnim = Collapse({
			this.class.pausedAnimations.remove(this);
			this.class.animations.add(this);
		}, 2);
	}

	connect {
		|inDevice|
		if (inDevice != device) {
			if (device.notNil) {
				this.disconnect();
			};

			device = inDevice;
			if (inDevice.notNil) {
				// "Connecting device(%) to knob(%)".format(device.identityHash, this.identityHash).postln;
				device = inDevice;
				hueAnimator = PropertyAnimator(device, \ledHue);
				brightnessAnimator = PropertyAnimator(device, \ledBrightness);

				deviceConnections = ConnectionList [
					device.signal(\knobRelative).connectTo(this.methodSlot("onKnobRelative(value)")),
					device.signal(\button).connectTo(this.methodSlot("onButton(value)")),
				];

				this.updateDevice();
			}

		}
	}

	disconnect {
		device.ledBrightness = 0;
		device.value = 0;
		deviceConnections.free;
		hueAnimator = brightnessAnimator = deviceConnections = device = nil;
	}

	onKnob {
		|value|
		knobCV !? { knobCV.input = value };

		if (animTarget.notNil) {
			this.class.animations.remove(this);
			this.class.pausedAnimations.add(this);
			resumeAnim.value();
		}
	}

	onKnobRelative {
		|value|
		var currentT;

		currentT = thisThread.seconds;

		knobCV !? {
			if ((currentT - lastInputT) > 0.5) {
				input = knobCV.input;
			};
			lastInputT = currentT;
			input = (input + (value * knobScale)).clip(0, 1);
			knobCV.input = input;
		};

		if (animTarget.notNil) {
			this.class.animations.remove(this);
			this.class.pausedAnimations.add(this);
			resumeAnim.value();
		}
	}

	onButton {
		|value|
		buttonCV !? {
			if (toggle) {
				if (value == \on) {
					buttonCV.toggle;
				}
			} {
				buttonCV.value = value
			}
		};
	}

	brightness_{
		|v|
		brightness = v;
		this.updateDevice();
	}

	cv {
		^this.knobCV
	}

	cv_{
		|cv|
		this.knobCV = cv;
	}

	knobCV_{
		|cv|
		knobCV = cv;
		input = cv.input;
		knobConnections.free.clear;

		if (knobCV.notNil) {
			knobConnections.add(
				knobCV.signal(\value).connectTo(this.methodSlot("updateValue"))
			);
			this.enable();
		} {
			this.disable();
		}
	}

	buttonCV_{
		|cv|
		buttonCV = cv;

		buttonConnections.free.clear;

		if (buttonCV.notNil) {
			buttonConnections.add(
				buttonCV.signal(\value).connectTo(this.methodSlot("updateLed"))
			)
		}
	}

	mapTo {
		|target, cvClass=(BusControlValue)|
		var def, spec, default;

		if (target.isKindOf(SynthArgSlot).not) {
			Error("'target' must be a SynthArgSlot.").throw
		};

		target.postln;
		target.synth.postln;
		if (target.synth.isKindOf(Ndef)) {
		target.synth.objects.postln;
			def = target.synth.objects[0].synthDef
		};
		if (target.synth.isKindOf(Synth)) {
			def = target.synth.def
		};

		def.postln;
		spec = def !? {|d| d.specs[target.argName]} ?? target.argName.tryPerform(\asSpec) ?? ControlSpec(0, 1);
		default = spec.default;

		spec.postln;

		if (knobCV.isNil) {
			this.knobCV = BusControlValue(spec:spec);
		} {
			this.knobCV.spec = spec;
		};

		target.synth.map(target.argName, knobCV.asMap);
	}

	enable {
		enabled = true;
		this.updateDevice();
	}

	disable {
		enabled = false;
		this.updateDevice();
	}

	ledColor_{
		|inColor|
		ledColor = inColor;
		this.updateDevice();
	}

	brightenLed {
		brightnessAnimator.set(ledColor.asHSV[2], 0);
	}

	dimLed {
		brightnessAnimator.set(ledColor.asHSV[2] * brightness, 0.2);
	}

	updateDevice {
		this.updateValue();
		this.updateLed();
	}

	updateValue {
		if (device.notNil) {
			if (knobCV.notNil) {
				device.value = knobCV.input;
				device.ringBrightness = knobCV.input.linlin(0, 1, 0.3, 0.8);
			} {
				device.value = 0;
				device.ringBrightness = 0;
			};
		}
	}

	updateLed {
		var hsv = ledColor.asHSV;

		if (device.notNil) {
			if (buttonCV.value == \on) {
				hueAnimator.set(hsv[0], 0.0);
				brightnessAnimator.set(hsv[2], 0.0);
			} {
				if (enabled) {
					hueAnimator.set(hsv[0], 0.2);
					brightnessAnimator.set(hsv[2] * brightness, 0.2);
				} {
					hueAnimator.set(hsv[0], 0.2);
					brightnessAnimator.set(0, 0.2);
				}
			}
		}
	}

	slew {
		|target, duration|
		animTarget = target;
		animRate = (knobCV.input - knobCV.spec.unmap(target)).abs / duration;

		this.class.animations = this.class.animations.add(this);

		this.class.animator ?? {
			this.class.animator = Routine({
				while { (this.class.animations.size + this.class.pausedAnimations.size) > 0 } {
					this.class.slewAnimate();
					0.05.wait;
				};
				this.class.animator = nil;
			});
			this.class.animator.play;
		};
	}

	*slewAnimate {
		animations.copy.do({ |k| k.slewAnimate(0.05) });
	}

	slewAnimate {
		|delta|
		var maxChange = animRate * delta;
		var deltaAmt;
		knobCV !? {
			if (knobCV.input < animTarget) {
				knobCV.input = (knobCV.input + min(maxChange, animTarget - knobCV.input));
			} {
				knobCV.input = (knobCV.input - min(maxChange, (animTarget - knobCV.input).abs));
			};

			if ((knobCV.input - animTarget).abs < 0.001) {
				knobCV.input = animTarget;
				this.class.animations.remove(this);
				animTarget = nil; animRate = nil;
			}
		}
	}
}

TwisterDevice : Singleton {
	classvar <endpointDevice="Midi Fighter Twister %", <endpointName="Midi Fighter Twister";
	classvar <deviceChangedConnection;
	classvar registeredDevices;

	var <knobs, <buttons;
	var <endpoint;

	*initClass {
		Class.initClassTree(MIDIWatcher);

		registeredDevices = ();
		deviceChangedConnection = ConnectionList();

		this.registerDevice(\default, endpointDevice.format(1), endpointName);
		this.registerDevice(\secondary, endpointDevice.format(2), endpointName)
	}

	*registerDevice {
		|name, endpointDevice, endpointName|

		if (registeredDevices[name].isNil) {
			registeredDevices[name] = [endpointDevice, endpointName];

			deviceChangedConnection.add(
				MIDIWatcher.deviceSignal(endpointDevice, endpointName).connectTo(
					this.methodSlot("deviceChanged(%, changed, value)".format("\\" ++ name))
				)
			)
		} {
			"TwisterDevice % is already registered.".format(name).error;
		}
	}

	*deviceChanged {
		|deviceName, changeType, endpoint|
		if (changeType == \sourceAdded) {
				"Added % MIDI Fighter Twister [%]".format(deviceName, endpoint.uid).postln;
				TwisterDevice(deviceName, endpoint);
				{ TwisterDevice(deviceName).connectAnimation(); }.defer(0.5)
		};

		if (changeType == \sourceRemoved) {
			"Removed MIDI Fighter Twister [%]".format(endpoint.uid).postln;
		}
	}

	init {
		knobs = 16.collect {
			|i|
			TwisterDeviceKnob(-1, DummyMIDI(), i)
		};
		buttons = (8..13).collect {
			|i|
			TwisterDeviceButton(-1, i)
		}
	}

	connectAnimation {
		var k = this.knobs.collect(TwisterDeviceKnob(_));
		fork {
			var dur = 1.5;
			(0,0.02..dur).do {
				|seconds|
				k.do {
					|knob, i|
					var hue, brightness;
					i = (i / 3).floor * 3;
					hue = (seconds / dur).pow(1.1 + (i / k.size)) *  1.5;
					brightness = (seconds / dur).pow(2.4 - (i / k.size * 2.2));
					knob.ledColor = Color.hsv(hue % 1, 1, brightness % 1);
				};
				0.02.wait;
			};
			0.1.wait;
			(0,0.02..0.5).do {
				|n|
				k.do({|k| k.ledBrightness = (0.5 - n) * 2 });
				0.02.wait;
			}
		}
	}

	set {
		|inEndpoint|
		var midiInUid, midiOut;

		endpoint = inEndpoint;

		if (endpoint.isNil) {
			midiInUid = 0;
			midiOut = DummyMIDI();
		} {
			MIDIIn.connectAll();
			midiInUid = endpoint.uid;

			midiOut = MIDIOut.newByName(endpoint.device, endpoint.name).latency_(0);

			knobs.do {
				|knob|
				knob.setMidiDevices(midiInUid, midiOut);
			};

			buttons.do {
				|button|
				button.setMidiDevices(midiInUid);
			};

			knobs.do(_.off());
		};

		this.changed(\deviceConnected);
	}

	rows {
		|x, y|
		var result = knobs.clump(4);

		if (x.notNil) {
			result = result[x];
			if (y.notNil) {
				result = result[y];
			}
		};

		^result;
	}

	cols {
		|y, x|
		var result = knobs.clump(4);

		if (y.notNil) {
			result = result[y];
			if (x.notNil) {
				result = result[x];
			}
		};

		^result;
	}

	knob {
		|x, y|
		^this.knobRows(x, y)
	}
}

TwisterDeviceButton {
	classvar buttonChan=3;

	var <cc, <midiInUid, <midiOut,
	<isOn = false, buttonFunc;

	*new {
		arg midiInUid, cc;
		var obj = super.newCopyArgs(cc).setMidiDevices(midiInUid);
		CmdPeriod.add(obj);

		^obj;
	}

	*from {
		|otherDevice|
		^this.new(otherDevice.midiInUid, otherDevice.cc);
	}

	setMidiDevices {
		|uid|

		if (midiInUid != uid) {
			midiInUid = uid;
		};

		this.makeMIDIFuncs();
	}

	doOnCmdPeriod {
		this.makeMIDIFuncs();
	}

	makeMIDIFuncs {
		buttonFunc.free;

		buttonFunc = MIDIFunc.cc({
			|val|
			this.changed(\button, (val == 0).if(\off, \on));
		}, cc, buttonChan, srcID:midiInUid);
	}
}

TwisterDeviceKnob {
	classvar brightnessChan=2, brightnessScale=#[17, 47],
	ringBrightnessChan=2, ringBrightnessScale=#[65, 95],
	hueChan=1, hueScale=#[1, 126],
	knobChan=0, buttonChan=1;

	var <cc, <midiInUid, <midiOut,
	<isOn = false, buttonFunc, knobFunc;

	var <ledHue=0, <ledBrightness=0, <ringBrightness=1;

	*new {
		arg midiInUid, midiOut, cc;
		var obj = super.newCopyArgs(cc).setMidiDevices(midiInUid, midiOut);
		CmdPeriod.add(obj);

		^obj;
	}

	*from {
		arg otherDevice;
		^this.new(otherDevice.midiInUid, otherDevice.midiOut, otherDevice.cc);
	}

	setMidiDevices {
		|uid, inMidiOut|

		if (midiInUid != uid) {
			midiInUid = uid;
		};

		this.makeMIDIFuncs();

		midiOut = inMidiOut ?? midiOut;
	}

	doOnCmdPeriod {
		this.makeMIDIFuncs();
	}

	makeMIDIFuncs {
		buttonFunc.free; knobFunc.free;

		buttonFunc = MIDIFunc.cc({
			|val|
			this.changed(\button, (val == 0).if(\off, \on));
		}, cc, buttonChan, srcID:midiInUid);

		knobFunc = MIDIFunc.cc({
			|value|
			this.changed(\knobRelative, value - 64);
		}, cc, knobChan, srcID:midiInUid);
	}

	ledBrightness_{
		|brightness|
		ledBrightness = brightness;
		midiOut	!? { midiOut.control(brightnessChan, cc, brightness.linlin(0, 1, *brightnessScale)) };
	}

	ledHue_{
		|hue, offset=0.33333|
		ledHue = hue;
		hue = (1.0 - hue - offset) % 1.0;
		midiOut !? { midiOut.control(hueChan, cc, hue.linlin(0, 1, *hueScale)) };
	}

	ledColor_{
		|color|
		var hsv = color.asHSV;
		this.ledHue = hsv[0].min(1).max(0);
		this.ledBrightness = hsv[2].min(1).max(0);
	}

	ringBrightness_{
		|brightness|
		ringBrightness = brightness;
		midiOut !? { midiOut.noteOn(ringBrightnessChan, cc, ringBrightness.linlin(0, 1, *ringBrightnessScale)) };
	}

	value_{
		|value|
		midiOut !? { midiOut.control(0, cc, (value * 127.0).round(1)); }
	}

	off {
		this.value = 0;
		this.ledBrightness = 0;
	}
}