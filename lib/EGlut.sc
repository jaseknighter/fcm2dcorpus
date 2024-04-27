//EGlut: grandchild of Glut, parent of ZGlut
//mostly, this adds grain envelopes to the ZGlut engine
EGlut {
  classvar ngvoices = 1;
  
  var s;
  var context;
  var pg;
	var effect;
	var <buffers;
	var <gvoices;
	var effectBus;
	var <phases;
	var <levels;
	var <gr_envbufs;
  var updating_gr_envbufs = false;
  var prev_pos_sig1=0, prev_pos_sig2=0, prev_pos_sig3=0, prev_pos_sig4=0;

	var <seek_tasks;

	*new {
		arg argServer, context, eng;
		^super.new.init(argServer,context,eng);

	}

	// turn audio signal into buffers
	fillEGBufs { arg i, buf;
		if(buffers[i].notNil, {
      ["fillEGBufs i,buf",i,buf].postln;
      // duplicate GrainBuf for stereo granulation
      gvoices[i].set(\buf, buf);
      buffers[i].zero;
      buffers[i] = buf;
      gvoices[i].set(\buf2, buf);
      buffers[i+ngvoices].zero;
      buffers[i+ngvoices] = buf;
		});
	}

  // disk read
	readBuf { arg i, path;
		if(buffers[i].notNil, {
			if (File.exists(path), {
				// load stereo files and duplicate GrainBuf for stereo granulation
        var newbuf,newbuf2;
        newbuf = Buffer.readChannel(context.server, path, 0, -1, [0], {
          buffers[i].free;
					gvoices[i].set(\buf, newbuf);
					buffers[i] = newbuf;
          ["newbuf",i,buffers[i]].postln;
				});
				// newbuf2 = Buffer.readChannel(context.server, path, 0, -1, [1], {
				newbuf2 = Buffer.readChannel(context.server, path, 0, -1, [0], {
					buffers[i+ngvoices].free;
				  gvoices[i].set(\buf2, newbuf2);
					buffers[i+ngvoices] = newbuf2;
				});
			});
		});
	}


  ///////////////////////////////////
  //init 
	init {
		arg argServer, engContext, eng;
    var thisEngine;
    var lua_sender;
    lua_sender = NetAddr.new("127.0.0.1",10111);   
    
		s=argServer;
    context = engContext;
    thisEngine = eng;
    
    buffers = Array.fill(ngvoices*2, { arg i;
      Buffer.alloc(
        s,
        s.sampleRate * 1,
      );
    });

    SynthDef(\synth, {
      arg out, effectBus, phase_out, level_out, buf, buf2,
      gate=0, pos=0, buf_pos_start=0, buf_pos_end=1, speed=1, jitter=0, spread_sig=0, voice_pan=0,	
      size=0.1, density=20, pitch=1, spread=0, gain=1, envscale=1,
      freeze=0, t_reset_pos=0, cutoff=20000, q, mode=0, send=0,
      subharmonics=0,overtones=0, gr_envbuf = -1,
      spread_sig_offset1=0, spread_sig_offset2=0, spread_sig_offset3=0;

      var grain_trig;
      var trig_rnd;
      var jitter_sig, jitter_sig2, jitter_sig3, jitter_sig4;
      var pos_sig1, pos_sig2, pos_sig3, pos_sig4;
      var buf_dur;
      var pan_sig;
      var pan_sig2;
      var buf_pos;
      var pos_sig;
      var sig;

      var env;
      var level;
      var grain_env;
      var main_vol=1.0/(1.0+subharmonics+overtones);
      var subharmonic_vol=subharmonics/(1.0+subharmonics+overtones);
      var overtone_vol=overtones/(1.0+subharmonics+overtones);

      density = Lag.kr(density);
      spread = Lag.kr(spread);
      size = Lag.kr(size);
      cutoff = Lag.kr(cutoff);
      q = Lag.kr(q);
      send = Lag.kr(send);
      pitch = Lag.kr(pitch,0.25);
      
      grain_trig = Impulse.kr(density);
      buf_dur = BufDur.kr(buf);

      pan_sig = TRand.kr(trig: grain_trig,
        lo: -1,
        hi: (2*spread)-1);

      pan_sig2 = TRand.kr(trig: grain_trig,
        lo: 1-(2*spread),
        hi: 1);

      jitter_sig = TRand.kr(trig: grain_trig,
        lo: buf_dur.reciprocal.neg * jitter,
        hi: buf_dur.reciprocal * jitter);
      jitter_sig2 = TRand.kr(trig: grain_trig,
        lo: buf_dur.reciprocal.neg * jitter,
        hi: buf_dur.reciprocal * jitter);
      jitter_sig3 = TRand.kr(trig: grain_trig,
        lo: buf_dur.reciprocal.neg * jitter,
        hi: buf_dur.reciprocal * jitter);
      jitter_sig4 = TRand.kr(trig: grain_trig,
        lo: buf_dur.reciprocal.neg * jitter,
        hi: buf_dur.reciprocal * jitter);

      buf_pos = Phasor.kr(trig: t_reset_pos,
        rate: buf_dur.reciprocal / ControlRate.ir * speed,
        start:buf_pos_start, end:buf_pos_end, resetPos: pos);

      pos_sig = Wrap.kr(Select.kr(freeze, [buf_pos, pos]));
      pos_sig1=(pos_sig+jitter_sig).wrap(0,1);
      pos_sig2=(pos_sig+jitter_sig2+(spread_sig)+spread_sig_offset1).wrap(0,1);
      pos_sig3=(pos_sig+jitter_sig3+(spread_sig*2)+spread_sig_offset2).wrap(0,1);
      pos_sig4=(pos_sig+jitter_sig4+(spread_sig*3)+spread_sig_offset3).wrap(0,1);
      // [pos_sig1,pos_sig2,pos_sig3,pos_sig4].poll;
      
      SendReply.kr(Impulse.kr(10), "/osc_pos_sigs", [pos_sig1, pos_sig2, pos_sig3, pos_sig4]);

      sig = GrainBuf.ar(
            numChannels: 2, 
            trigger:grain_trig, 
            dur:size, 
            sndbuf:buf, 
            pos: pos_sig1,
            interp: 2, 
            pan: pan_sig,
            rate:pitch,
            envbufnum:gr_envbuf,
            maxGrains:96,
            mul:main_vol,
          )+
          GrainBuf.ar(
            numChannels: 2, 
            trigger:grain_trig, 
            dur:size, 
            sndbuf:buf2, 
            pos: pos_sig1, 
            interp: 2, 
            pan: pan_sig2,
            rate:pitch,
            envbufnum:gr_envbuf,
            maxGrains:96,
            mul:main_vol,
          )+


          GrainBuf.ar(
            numChannels: 2, 
            trigger:grain_trig, 
            dur:size, 
            sndbuf:buf, 
            pos: pos_sig2, 
            interp: 2, 
            pan: pan_sig,
            rate:pitch,
            envbufnum:gr_envbuf,
            maxGrains:72,
            mul:main_vol*0.5,
          )+
          GrainBuf.ar(
            numChannels: 2, 
            trigger:grain_trig, 
            dur:size, 
            sndbuf:buf2, 
            pos: pos_sig2, 
            interp: 2, 
            pan: pan_sig2,
            rate:pitch,
            envbufnum:gr_envbuf,
            maxGrains:72,
            mul:main_vol*0.5,
          )+
        GrainBuf.ar(
            numChannels: 2, 
            trigger:grain_trig, 
            dur:size, 
            sndbuf:buf, 
            pos: pos_sig3, 
            interp: 2, 
            pan: pan_sig,
            rate:pitch,
            envbufnum:gr_envbuf,
            maxGrains:32,
            mul:main_vol*0.5,
          )+
          GrainBuf.ar(
            numChannels: 2, 
            trigger:grain_trig, 
            dur:size, 
            sndbuf:buf2, 
            pos: pos_sig3, 
            interp: 2, 
            pan: pan_sig2,
            rate:pitch,
            envbufnum:gr_envbuf,
            maxGrains:32,
            mul:main_vol*0.5,
          )+
        GrainBuf.ar(
            numChannels: 2, 
            trigger:grain_trig, 
            dur:size, 
            sndbuf:buf, 
            pos: pos_sig4, 
            interp: 2, 
            pan: pan_sig,
            rate:pitch,
            envbufnum:gr_envbuf,
            maxGrains:24,
            mul:main_vol*0.5,
          )+
          GrainBuf.ar(
            numChannels: 2, 
            trigger:grain_trig, 
            dur:size, 
            sndbuf:buf2, 
            pos: pos_sig4, 
            interp: 2, 
            pan: pan_sig2,
            rate:pitch,
            envbufnum:gr_envbuf,
            maxGrains:24,
            mul:main_vol*0.5,
          )



          +
          GrainBuf.ar(
            numChannels: 2, 
            trigger:grain_trig, 
            dur:size, 
            sndbuf:buf, 
            pos: pos_sig2, 
            interp: 2, 
            pan: pan_sig,
            rate:pitch/2,
            envbufnum:gr_envbuf,
            maxGrains:72,
            mul:subharmonic_vol,
          )+
          GrainBuf.ar(
            numChannels: 2, 
            trigger:grain_trig, 
            dur:size, 
            sndbuf:buf2, 
            pos: pos_sig2, 
            interp: 2, 
            pan: pan_sig2,
            rate:pitch/2,
            envbufnum:gr_envbuf,
            maxGrains:72,
            mul:subharmonic_vol,
          )+
        GrainBuf.ar(
            numChannels: 2, 
            trigger:grain_trig, 
            dur:size, 
            sndbuf:buf, 
            pos: pos_sig3, 
            interp: 2, 
            pan: pan_sig,
            rate:pitch*2,
            envbufnum:gr_envbuf,
            maxGrains:32,
            mul:overtone_vol*0.7,
          )+
          GrainBuf.ar(
            numChannels: 2, 
            trigger:grain_trig, 
            dur:size, 
            sndbuf:buf2, 
            pos: pos_sig3, 
            interp: 2, 
            pan: pan_sig2,
            rate:pitch*2,
            envbufnum:gr_envbuf,
            maxGrains:32,
            mul:overtone_vol*0.7,
          )+
        GrainBuf.ar(
            numChannels: 2, 
            trigger:grain_trig, 
            dur:size, 
            sndbuf:buf, 
            pos: pos_sig4, 
            interp: 2, 
            pan: pan_sig,
            rate:pitch*4,
            envbufnum:gr_envbuf,
            maxGrains:24,
            mul:overtone_vol*0.3,
          )+
          GrainBuf.ar(
            numChannels: 2, 
            trigger:grain_trig, 
            dur:size, 
            sndbuf:buf2, 
            pos: pos_sig4, 
            interp: 2, 
            pan: pan_sig2,
            rate:pitch*4,
            envbufnum:gr_envbuf,
            maxGrains:24,
            mul:overtone_vol*0.3,
          )
          ;
            // maxGrains:[128,256,64,128,64]/2,
            // mul:[0.125,0.625,0.05,0.15,0.05]/2,
      
      

      sig = BLowPass4.ar(sig, cutoff, q);
      sig = Compander.ar(sig,sig,0.25)/8;
      sig = Balance2.ar(sig[0],sig[1],voice_pan);
      env = EnvGen.kr(Env.asr(1, 1, 1), gate: gate, timeScale: envscale);

      level = env;
      Out.ar(out, sig * level * gain);
      Out.ar(effectBus, sig * level * send );
      Out.kr(phase_out, pos_sig);
      // ignore gain for level out
      Out.kr(level_out, level);
    }).add;

    SynthDef(\effect, {
      arg in, out, delayTime=2.0, damp=0.1, size=4.0, diff=0.7, feedback=0.2, modDepth=0.1, modFreq=0.1, delayVol=1.0;
      var sig = In.ar(in, 2);
      // sig = CombL.ar(in: sig, maxdelaytime: 1, delaytime: delayTime, decaytime: damp, mul: 1.0, add: 0.0);

      // sig = Greyhole.ar(sig, delayTime, damp, size, diff, feedback, modDepth, modFreq);
      Out.ar(out, sig * delayVol);
    }).add;

    s.sync;

    // delay bus
    effectBus = Bus.audio(context.server, 2);
    
    effect = Synth.new(\effect, [\in, effectBus.index, \out, context.out_b.index], target: context.xg);

    phases = Array.fill(ngvoices, { arg i; Bus.control(context.server); });
    levels = Array.fill(ngvoices, { arg i; Bus.control(context.server); });
    gr_envbufs = Array.fill(ngvoices, { arg i; 
      var winenv = Env([0, 1, 0], [0.5, 0.5], [\wel, \wel]);
      Buffer.sendCollection(s, winenv.discretize, 1);
    });

    pg = ParGroup.head(context.xg);

    gvoices = Array.fill(ngvoices, { arg i;
      Synth.new(\synth, [
        \out, context.out_b.index,
        \effectBus, effectBus.index,
        \phase_out, phases[i].index,
        \level_out, levels[i].index,
        \buf, buffers[i],
        \buf2, buffers[i+ngvoices],
        \gr_envbuf, -1
        // \gr_envbuf, gr_envbufs[i]
      ], target: pg);
    });

    context.server.sync;

    thisEngine.addCommand("delay_time", "f", { arg msg; effect.set(\delayTime, msg[1]); });
    thisEngine.addCommand("delay_damp", "f", { arg msg; effect.set(\damp, msg[1]); });
    thisEngine.addCommand("delay_size", "f", { arg msg; effect.set(\size, msg[1]); });
    thisEngine.addCommand("delay_diff", "f", { arg msg; effect.set(\diff, msg[1]); });
    thisEngine.addCommand("delay_fdbk", "f", { arg msg; effect.set(\feedback, msg[1]); });
    thisEngine.addCommand("delay_mod_depth", "f", { arg msg; effect.set(\modDepth, msg[1]); });
    thisEngine.addCommand("delay_mod_freq", "f", { arg msg; effect.set(\modFreq, msg[1]); });
    thisEngine.addCommand("delay_volume", "f", { arg msg; effect.set(\delayVol, msg[1]); });

    thisEngine.addCommand("read", "is", { arg msg;
      this.readBuf(msg[1] - 1, msg[2]);
    });

    thisEngine.addCommand("seek", "if", { arg msg;
      var voice = msg[1] - 1;
      var lvl, pos;
      var seek_rate = 1 / 750;

      seek_tasks[voice].stop;

      // TODO: async get
      lvl = levels[voice].getSynchronous();

      if (false, { // disable seeking until fully implemented
        var step;
        var target_pos;

        // TODO: async get
        pos = phases[voice].getSynchronous();
        gvoices[voice].set(\freeze, 1);

        target_pos = msg[2];
        step = (target_pos - pos) * seek_rate;

        seek_tasks[voice] = Routine {
          while({ abs(target_pos - pos) > abs(step) }, {
            pos = pos + step;
            gvoices[voice].set(\pos, pos);
            seek_rate.wait;
          });

          gvoices[voice].set(\pos, target_pos);
          gvoices[voice].set(\freeze, 0);
          gvoices[voice].set(\t_reset_pos, 1);
        };

        seek_tasks[voice].play();
      }, {
        pos = msg[2];

        gvoices[voice].set(\pos, pos);
        gvoices[voice].set(\t_reset_pos, 1);
        gvoices[voice].set(\freeze, 0);
      });
    });

    thisEngine.addCommand("gate", "ii", { arg msg;
      var voice = msg[1] - 1;
      gvoices[voice].set(\gate, msg[2]);
    });

    thisEngine.addCommand("speed", "if", { arg msg;
      var voice = msg[1] - 1;
      gvoices[voice].set(\speed, msg[2]);
    });

    thisEngine.addCommand("spread_sig", "if", { arg msg;
      var voice = msg[1] - 1;
      gvoices[voice].set(\spread_sig, msg[2]);
    });

    thisEngine.addCommand("spread_sig_offset1", "if", { arg msg;
      var voice = msg[1] - 1;
      gvoices[voice].set(\spread_sig_offset1, msg[2]);
    });

    thisEngine.addCommand("spread_sig_offset2", "if", { arg msg;
      var voice = msg[1] - 1;
      gvoices[voice].set(\spread_sig_offset2, msg[2]);
    });

    thisEngine.addCommand("spread_sig_offset3", "if", { arg msg;
      var voice = msg[1] - 1;
      gvoices[voice].set(\spread_sig_offset3, msg[2]);
    });

    thisEngine.addCommand("jitter", "if", { arg msg;
      var voice = msg[1] - 1;
      gvoices[voice].set(\jitter, msg[2]);
    });

    thisEngine.addCommand("size", "if", { arg msg;
      var voice = msg[1] - 1;
      gvoices[voice].set(\size, msg[2]);
    });

    thisEngine.addCommand("density", "if", { arg msg;
      var voice = msg[1] - 1;
      gvoices[voice].set(\density, msg[2]);
    });

    thisEngine.addCommand("pan", "if", { arg msg;
      var voice = msg[1] - 1;
      gvoices[voice].set(\voice_pan, msg[2]);
    });

    thisEngine.addCommand("pitch", "if", { arg msg;
      var voice = msg[1] - 1;
      gvoices[voice].set(\pitch, msg[2]);
    });

    thisEngine.addCommand("spread", "if", { arg msg;
      var voice = msg[1] - 1;
      gvoices[voice].set(\spread, msg[2]);
    });

    thisEngine.addCommand("gain", "if", { arg msg;
      var voice = msg[1] - 1;
      var gain_mul = 4;
      gvoices[voice].set(\gain, msg[2]*gain_mul);
    });

    thisEngine.addCommand("gr_envbuf", "ifffff", { arg msg;
      var voice = msg[1] - 1;
      var attack_level = msg[2];
      var attack_time = msg[3];
      var decay_time = msg[4];
      var attack_shape = msg[5]-1;
      var decay_shape = msg[6]-1;
      var oldbuf;
      var attack_curve_types=["step","lin","sin","wel","squared","cubed"];
      var decay_curve_types=["step","lin","exp","sin","wel","squared","cubed"];
      var winenv = Env(
        [0, attack_level, 0], 
        [attack_time, decay_time], 
        // [attack_shape, curve_types[decay_shape].asSymbol]
        [attack_curve_types[attack_shape].asSymbol, decay_curve_types[decay_shape].asSymbol]
      );

      // [attack_shape,decay_shape,attack_curve_types[attack_shape].asSymbol, decay_curve_types[decay_shape].asSymbol].postln;
      if (updating_gr_envbufs == false,{
        updating_gr_envbufs = true;
        oldbuf = gr_envbufs[voice];
        gr_envbufs[voice] = Buffer.sendCollection(s, winenv.discretize, 1);
        Routine({
          0.1.wait;
          gvoices[voice].set(\gr_envbuf, gr_envbufs[voice]);
          1.wait;
          updating_gr_envbufs = false;
          oldbuf.free;
        }).play;
        // FluidBufCompose.process(s, winenv.discretize, startChan: 0, numChans: 1, gain: 1, destination: gr_envbufs[voice], destStartFrame: 0, destStartChan: 0, destGain: 0, action: {
        //   ["gr_envbuf",gr_envbufs[voice],msg].postln;
        // });

      })
    });

    thisEngine.addCommand("envscale", "if", { arg msg;
      var voice = msg[1] - 1;
      gvoices[voice].set(\envscale, msg[2]);
    });
    
    thisEngine.addCommand("cutoff", "if", { arg msg;
    var voice = msg[1] -1;
    gvoices[voice].set(\cutoff, msg[2]);
    });
    
    thisEngine.addCommand("q", "if", { arg msg;
    var voice = msg[1] -1;
    gvoices[voice].set(\q, msg[2]);
    });
    
    thisEngine.addCommand("send", "if", { arg msg;
    var voice = msg[1] -1;
    gvoices[voice].set(\send, msg[2]);
    });
    
    thisEngine.addCommand("volume", "if", { arg msg;
      var voice = msg[1] - 1;
      gvoices[voice].set(\gain, msg[2]);
    });
    
    thisEngine.addCommand("overtones", "if", { arg msg;
      var voice = msg[1] - 1;
      gvoices[voice].set(\overtones, msg[2]);
    });
    
    thisEngine.addCommand("subharmonics", "if", { arg msg;
      var voice = msg[1] - 1;
      gvoices[voice].set(\subharmonics, msg[2]);
    });

    ngvoices.do({ arg i;
      thisEngine.addPoll(("phase_" ++ (i+1)).asSymbol, {
        var val = phases[i].getSynchronous;
        val
      });

    // 	thisEngine.addPoll(("level_" ++ (i+1)).asSymbol, {
    // 		var val = levels[i].getSynchronous;
    // 		val
    // 	});
    });

    seek_tasks = Array.fill(ngvoices, { arg i;
      Routine {}
    });

    OSCdef(\osc_pos_sigs, {|msg| 
      var pos_sig1 = msg[3];
      var pos_sig2 = msg[4];
      var pos_sig3 = msg[5];
      var pos_sig4 = msg[6];
      if(
        pos_sig1 != prev_pos_sig1 || 
        pos_sig2 != prev_pos_sig2 || 
        pos_sig3 != prev_pos_sig3 || 
        pos_sig4 != prev_pos_sig4, {
        lua_sender.sendMsg("/lua_fcm2dcorpus/grain_pos_sig",pos_sig1, pos_sig2, pos_sig3, pos_sig4);
      });
      prev_pos_sig1 = pos_sig1;
      prev_pos_sig2 = pos_sig2;
      prev_pos_sig3 = pos_sig3;
      prev_pos_sig4 = pos_sig4;
    }, "/osc_pos_sigs");

  }

  ///////////////////////////////////

  free{
    gvoices.do({ arg voice; voice.free; });
    phases.do({ arg bus; bus.free; });
    levels.do({ arg bus; bus.free; });
    buffers.do({ arg b; b.free; });
    gr_envbufs.do({ arg b; b.free; });
    effect.free;
    effectBus.free;

  }
}