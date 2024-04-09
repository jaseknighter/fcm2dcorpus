// Engine_FCM2dCorpus

// Inherit methods from CroneEngine
Engine_FCM2dCorpus : CroneEngine {
  var point, previous;
  var src,normedBuf;
  var osc_funcs;
  var recorder;
  var osccaller=0;
  var findices;
  var playing_slice=false;
  var grBuf;

	*new { arg context, doneCallback;
		^super.new(context, doneCallback);
	}

  alloc {
    var s = context.server;
    var players;
    var compose, writebuf, recordlive, analyze, play_slice, lua_sender,sc_sender;
    var tree;
    var composewritebufdone, composelivewritebufdone, analyzewritebufdone;
    var current;    
    var indices = Buffer.new(s);

    grBuf = Buffer.alloc(s, s.sampleRate * 2);

    osc_funcs = Dictionary.new();
    recorder = Dictionary.new();
    players = Dictionary.new();
    
    ["memsize",s.options.memSize].postln;
    s.options.memSize  = 8192*4; 
    ["memsize post",s.options.memSize].postln;
    lua_sender = NetAddr.new("127.0.0.1",10111);   
    sc_sender = NetAddr.new("127.0.0.1",57120);   
    lua_sender.sendMsg("/lua_fcm2dcorpus/sc_inited");
    osccaller=osccaller+1;
        


    composewritebufdone = {
      "compose writebufdone".postln;
      lua_sender.sendMsg("/lua_fcm2dcorpus/compose_written",Platform.defaultTempDir+/+"src.wav");
    };

    composelivewritebufdone = {
      "compose live writebufdone".postln;
      lua_sender.sendMsg("/lua_fcm2dcorpus/composelive_written",Platform.defaultTempDir+/+"live.wav");
    };

    analyzewritebufdone = {
      "analyze writebufdone".postln;
      lua_sender.sendMsg("/lua_fcm2dcorpus/analyze_written");
    };

    writebuf = {
      arg buf, path, header_format, sample_format,msg;
      buf.normalize();
      if (msg == "compose",{
        buf.write(path, header_format, sample_format,completionMessage:composewritebufdone);
      });
      if (msg == "composelive",{
        "write live recording".postln;
        buf.write(path, header_format, sample_format,completionMessage:composelivewritebufdone);
      });
      if (msg == "analyze",{
        buf.write(path, header_format, sample_format,completionMessage:analyzewritebufdone);
      });
    };
    
    SynthDef(\recordlive, { | buf, rate=1, secs=10.0|
      var dur = s.sampleRate * secs;
      // var in= SoundIn.ar([0,1]);
      var in= SoundIn.ar(0);
      RecordBuf.ar(in,buf, loop:0, doneAction:2);
      "play record live".postln;
      0.0 //quiet
    }).add;

    
    compose = {
      arg folder_path, file_path;
      var loader;
      var src_path=Platform.defaultTempDir+/+"src.wav";
      var header_format="WAV";
      var sample_format="int24";
      src = Buffer.new(s);
      
      fork{
        "start loader".postln;
        if (folder_path != nil,{
          "load folder".postln;
          folder_path.postln;
          loader = FluidLoadFolder(folder_path).play(s);
          s.sync;
            ["loader loaded"].postln;
            "set mono src buffer".postln;
            if(loader.buffer.numChannels > 1){
              "stereo to mono".postln;
              src = Buffer.new(s);
              FluidBufCompose.process(s,loader.buffer,numFrames: loader.buffer.sampleRate*60*2,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
                ("buf composed1").postln;
                FluidBufCompose.process(s,loader.buffer,numFrames: loader.buffer.sampleRate*60*2,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                  ("buf composed2").postln;
                  ("audio composition completed").postln;
                  // src.write(Platform.defaultTempDir+/+"src.wav", "WAV", 'int16', completionMessage:{lua_sender.sendMsg("/lua_fcm2dcorpus/src_written",Platform.defaultTempDir+/+"src.wav")});
                  writebuf.(src,src_path,header_format,sample_format,"compose");
                });
              });
            }{
              "audio is already mono".postln;
              ("audio composition completed").postln;
              // src.write(Platform.defaultTempDir+/+"src.wav", "WAV", 'int16', completionMessage:{lua_sender.sendMsg("/lua_fcm2dcorpus/src_written",Platform.defaultTempDir+/+"src.wav")});
              writebuf.(src,src_path,header_format,sample_format,"compose");
            };
        },{
          ["load file",folder_path, file_path].postln;
          src = Buffer.read(s,file_path);
          s.sync;
          ["file loaded",src.numChannels,src.numFrames,src.sampleRate].postln;
          if(src.numChannels > 1){
            "stereo to mono".postln;
            FluidBufCompose.processBlocking(s,src,numFrames: src.sampleRate*60*2,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
              ("buf composed1").postln;
              FluidBufCompose.processBlocking(s,src,numFrames: src.sampleRate*60*2,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                ("buf composed2").postln;
                ("audio composition completed").postln;
                // src.write(Platform.defaultTempDir+/+"src.wav", "WAV", 'int16', completionMessage:{lua_sender.sendMsg("/lua_fcm2dcorpus/src_written",Platform.defaultTempDir+/+"src.wav")});
                writebuf.(src,src_path,header_format,sample_format,"compose");
              });
            });
          }{
            "audio is already mono".postln;
            // src.write(Platform.defaultTempDir+/+"src.wav", "WAV", 'int16', completionMessage:{lua_sender.sendMsg("/lua_fcm2dcorpus/src_written",Platform.defaultTempDir+/+"src.wav")});
            ("mono audio composition completed").postln;
            writebuf.(src,src_path,header_format,sample_format,"compose");
          };
          // });
        });
      };
    };

    analyze = {
      arg sliceThresh = 0.5;
      var analyses, normed, umapped, normed_dict;
  
      indices = Buffer.new(s);
      point = Buffer.alloc(s,2);
      fork{
        ["slice and analyze",indices].postln;
        // s.sync;

        src.postln;
        0.5.wait;
        "start FluidBufOnsetSlice".postln;
        FluidBufOnsetSlice.process(s,src,numChans: 1,metric:0,threshold:sliceThresh,indices:indices,windowSize:1024,hopSize: 1024*2, action:{
        // FluidBufOnsetSlice.process(s,src,numChans: 1,metric:0,threshold:sliceThresh,indices:indices,action:{
          "FluidBufOnsetSlice done".postln;
          "average seconds per slice: %".format(src.duration / indices.numFrames).postln;
          // analysis
          analyses = FluidDataSet(s).clear;
          ["start analysis"].postln;
          indices.loadToFloatArray(action:{
            arg fa;
            var umap_iterations = 10;
            var mfccs = Buffer(s);
            var stats = Buffer(s);
            var flat = Buffer(s);
            findices=fa;
            ["fa"].postln;
            
            fa.doAdjacentPairs{
              arg start, end, i;
              var num = end - start;

              FluidBufMFCC.processBlocking(s,src,start,num,features:mfccs,numCoeffs:13,startCoeff:1,action:{
                FluidBufStats.processBlocking(s,mfccs,stats:stats,select:[\mean],action:{
                  FluidBufFlatten.processBlocking(s,stats,destination:flat,action:{
                    analyses.addPoint(i,flat);
                    if((i)==fa.size){
                      lua_sender.sendMsg("/lua_fcm2dcorpus/analysis_progress",i,fa.size);
                    };
                    if((i%25)==24){
                      "slice % / %".format(i,fa.size).postln;
                      "slice % / %".format(i,fa.size).postln;
                      "slice % / %".format(i,fa.size).postln;
                      "slice % / %".format(i,fa.size).postln;
                      // s.sync;
                      lua_sender.sendMsg("/lua_fcm2dcorpus/analysis_progress",i,fa.size);
                    };

                  });
                });
              });

              s.sync;
            };

            s.sync;
            (">>>>>>>>>>>>analyses done<<<<<<<<<<<").postln;

            analyses.postln;
            "create umapped".postln;
            umapped = FluidDataSet(s);
            "umapped created".postln;
            FluidUMAP(s,numDimensions:2,numNeighbours:15,minDist:0.9,iterations:umap_iterations).fitTransform(analyses,umapped);
            "umap done".postln;
            // umapped.print;
            normed = FluidDataSet(s);
            FluidNormalize(s).fitTransform(umapped,normed);

            "normed".postln;
            normed.postln;

            tree = FluidKDTree(s, numNeighbours:1, radius:0.5).fit(normed);
            s.sync;
            ["tree set",tree].postln;
            // // normed_dict.postln;

            // s.sync;
            ["start normed dump",normed,fa].postln;
            ////////////////////////
            normed.dump({ arg dict;
              var dictkeys;
              var deststarts=List.new();
              var durations=List.new();
              normedBuf = Buffer(s);
              //save the buffer in the order of the dict
              dictkeys=dict.values[0].keys;
              dictkeys=Array.newFrom(dictkeys);
              ["indices.loadToFloatArray",dictkeys].postln;
              fa.doAdjacentPairs{
                arg start, end, i;
                var num = end - start;
                durations.add(num);
              };
              // s.sync;
              "durations captured".postln;
              dictkeys.do({|key,i|
                var startsamp,stopsamp, start_frame, duration, dest_start, lastduration=0, laststart;
                start_frame = fa[key.asInteger];
                duration = durations[key.asInteger];
                if(i>0,{
                  lastduration = durations[dictkeys[i-1].asInteger];
                  laststart = deststarts[i-1];
                  dest_start = lastduration+laststart;
                  deststarts.insert(i,dest_start);
                  // ([key,i,lastduration, laststart, deststarts,farr[key.asInteger]]).postln;
                  FluidBufCompose.process(s,src,startFrame:start_frame,numFrames: duration,destination:normedBuf,destStartFrame:dest_start, action:{});
                  // FluidBufCompose.processBlocking(s,src,startFrame:start_frame,numFrames: duration,destination:normedBuf,destStartFrame:dest_start, action:{["composed",i].postln});

                },{
                  deststarts.insert(i,0);
                  FluidBufCompose.processBlocking(s,src,startFrame:start_frame,numFrames: duration,destination:normedBuf,destStartFrame:0, action:{});
                  // FluidBufCompose.processBlocking(s,src,startFrame:start_frame,numFrames: duration,destination:normedBuf,destStartFrame:0, action:{["composed",i].postln});
                });
              });
            },action:{

            });
            // s.sync;
            ["analysis all done"].postln;
            normed.write(Platform.defaultTempDir+/+"temp_dataset.json");
            "normed json file generated".postln;
            [Platform.defaultTempDir+/+"temp_dataset.json"].postln;
            
            lua_sender.sendMsg("/lua_fcm2dcorpus/analysis_dumped");

            /////////////////////////
          });
        });
      };
    };

    osc_funcs.put("set_2dcorpus",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var path = msg[1].asString;
        var path_type = msg[2].asString;
        // var folder_path="/home/we/dust/code/fcm2dcorpus/lib/audio/";
        if (path_type == "folder",{
          (["call compose: folder",path]).postln;
          compose.(path,nil);
        },{
          (["call compose: file",path]).postln;
          compose.(nil,path);
        });
      }, "/sc_fcm2dcorpus/set_2dcorpus");
    );

    osc_funcs.put("write_src",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var path=msg[1];
        var header_format="WAV";
        var sample_format="int24";
        ["write_source",src,path,header_format,sample_format].postln;
        writebuf.(src,path,header_format,sample_format,"analyze");
      }, "/sc_fcm2dcorpus/write_src");
    );
    
    osc_funcs.put("write_normed",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var path=msg[1];
        var header_format="WAV";
        var sample_format="int24";
        ["write_src",normedBuf,path,header_format,sample_format].postln;
        writebuf.(normedBuf,path,header_format,sample_format,"analyze");
      }, "/sc_fcm2dcorpus/write_normed");
    );

    osc_funcs.put("analyze_2dcorpus",
      OSCFunc.new({ |msg, time, addr, recvPort|
        analyze.();
      }, "/sc_fcm2dcorpus/analyze_2dcorpus");
    );

    osc_funcs.put("record_live",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var secs=msg[1];
        var path=Platform.defaultTempDir+/+"live.wav";
        var header_format="WAV";
        var sample_format="int24";
        players.keysValuesDo({ arg k, val;
          val.set(\gate,0);
        });

        src = Buffer.alloc(s, s.sampleRate * secs, 1);

        recorder.add(\recorder ->
          Synth(\recordlive,[\secs,secs,\buf,src]);
        );

        
        Routine{
          secs.wait;
          ["recording completed",src].postln;
          writebuf.(src,path,header_format,sample_format,"composelive");
        }.play;

      }, "/sc_fcm2dcorpus/record_live");
    );

     SynthDef("transport_player", { |xloc=0.5,yloc=0.5,src,grBuf,index=0,rate=1,gate=1,startsamp,stopsamp,startsampL,stopsampL,windowSize = 512, hopSize = 256, fftSize = 512,tamp=1|
      var ptrdelay=0.2;
      var phs = Phasor.ar(0,BufRateScale.kr(src)*rate,startsamp,stopsamp);
      var sig = BufRd.ar(1,src,phs);
      var dursecs = min((stopsamp - startsamp) / BufSampleRate.kr(src),2);
      var env,outenv;
      var grains;
      var rates=[-2,-1.5,-1.2,-1,-0.5,0.5,1,1.2,1.5,2];

      var phsAdd = LFNoise0.kr(5).range(0,dursecs*10*BufSampleRate.kr(src));
      var phsL = Phasor.ar(0,BufRateScale.kr(src)*rate,startsampL+phsAdd,stopsampL+phsAdd);
      
      var sigL = BufRd.ar(1,src,phsL);
      var dursecsL = min((stopsampL - startsampL) / BufSampleRate.ir(src),2);
      var transportSig,transportGrains;
      var tpan,tenv;
      var gTrig = yloc.linlin(0,1,60,1);
      var ptr;
              



      env = EnvGen.kr(Env([0,1,1,0],[0.03,dursecs-0.06,0.03]),gate:gate* Trig.kr(Impulse.kr(2)));


      transportSig = FluidAudioTransport.ar(sig,sigL,xloc,windowSize,hopSize,fftSize);

      
      tpan = xloc.linlin(0,1,-1, 1);
      // tenv = EnvGen.kr(
      //   // Env([0, 1, 0], [(1/30)/2, (1/30)/2], \sin, 1),
      //   Env([0, 1, 0], [dursecs, 0], \sin, 1),
      //   gate,
      //   // levelScale: tamp,
      //   doneAction: 2
      // );

      
      // BufWr.ar(inputArray, bufnum: 0, phase: 0.0, loop: 1.0)
      ptr = Phasor.ar(trig:Impulse.kr(0.5), rate:BufRateScale.ir(grBuf), start:0, end:BufFrames.ir(grBuf), resetPos:0);
      BufWr.ar(inputArray:transportSig, bufnum: grBuf, phase: ptr, loop: 1);

      // transportGrains = GrainBuf.ar(numChannels:1,trigger:Impulse.kr(10), dur:xloc.linlin(0,1,1,0.1), sndbuf:grBuf, rate:xloc.linlin(0,1,0,2),maxGrains:100)*tenv;
      // transportGrains = BufGrain.ar(Impulse.kr(gTrig), 0.2, rec, rate);
      
      // GrainBuf.ar(numChannels: 1, trigger: 0, dur: 1, sndbuf, rate: 1, pos: 0, interp: 2, pan: 0, envbufnum: -1, maxGrains: 512, mul: 1, add: 0)
      transportGrains = GrainBuf.ar(
        numChannels:1,
        trigger:Impulse.kr(xloc.linlin(0,1,20,1)), 
        dur:xloc.linlin(0,1,0.05,0.5), 
        sndbuf:grBuf, 
        rate:rate,
        pos: (ptr-(ptrdelay+SampleRate.ir))/BufFrames.ir(grBuf),
        maxGrains:100
      );


      transportSig=(transportSig.dup*env * yloc)+(transportGrains*(1-yloc));
      // transportSig=transportGrains*(1-yloc);
      Out.ar([0,1],transportSig.dup*env);
    }).add;

    s.sync;

     //play a slice
    play_slice = {
      arg index;
      {
        var startsamp = Index.kr(indices,index);
        var stopsamp = Index.kr(indices,index+1);
        var phs = Phasor.ar(0,BufRateScale.ir(src),startsamp,stopsamp);
        var sig = BufRd.ar(1,src,phs);
        var dursecs = (stopsamp - startsamp) / BufSampleRate.ir(src);
        var env;
        dursecs = min(dursecs,1);
        env = EnvGen.kr(Env([0,1,1,0],[0.03,dursecs-0.06,0.03]),doneAction:2);
        sig.dup * env;
      }.play;
    };

    osc_funcs.put("play_slice",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var x=msg[1];
        var y=msg[2];
        point.setn(0,[x,y]);
        tree.kNearest(point,1,{
          arg nearest;
          if(nearest != previous){
            play_slice.(nearest.asInteger);
            previous = nearest;
            lua_sender.sendMsg("/lua_fcm2dcorpus/slice_played",current,previous);
          }
        });
      }, "/sc_fcm2dcorpus/play_slice");
    );


    osc_funcs.put("transport_slice",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var x=msg[1];
        var y=msg[2];
        
        point.setn(0,[x,y]);
        //note: error may be related to: https://discourse.flucoma.org/t/knearest-issue/1508/5
        tree.kNearest(point,1,action:{
          arg nearest;
          var startsamp,stopsamp;
    			var startsampL,stopsampL;
          if(playing_slice == false,{
            // "transport slice".postln;
            if (players.at(\currentPlayer)!=nil,{
              players.at(\currentPlayer).postln;
              startsampL=findices[current.asInteger];
              stopsampL=findices[current.asInteger+1];
              players.at(\currentPlayer).set(\gate,0);
              players.at(\currentPlayer).free;
            },{
              startsampL=findices[nearest.asInteger];
              stopsampL=findices[nearest.asInteger+1];
            });
            startsamp=findices[nearest.asInteger];
            stopsamp=findices[nearest.asInteger+1];
            // players.add(\currentPlayer->Synth(
            players.add(\nearestPlayer->Synth(
              \transport_player,[
                \xloc,x,
                \yloc,y,
                \src,src,
                \grBuf,grBuf.zero,
                \index, nearest.asInteger,
                \startsamp,startsamp,
                \stopsamp,stopsamp,
                \startsampL,startsampL,
                \stopsampL,stopsampL,
              ]
            ));
            Routine({
              playing_slice = true;
              lua_sender.sendMsg("/lua_fcm2dcorpus/slice_played",nearest,current);
              current = nearest;

              players.add(\currentPlayer->players.at(\nearestPlayer));
              players.add(\nearestPlayer->nil);
              // players.at(\nearestPlayer).postln;
              0.1.wait;
              playing_slice = false;
            }).play
          });
        });
      }, "/sc_fcm2dcorpus/transport_slice");
    );

    osc_funcs.put("transport_slice_x_y",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var x=msg[1];
        var y=msg[2];
        players.at(\currentPlayer).set(\xloc,x);
        players.at(\currentPlayer).set(\yloc,y);
      }, "/sc_fcm2dcorpus/transport_slice_x_y");
    );
    
    free {
      "free FCM2dCorpus".postln;  
      osc_funcs.keysValuesDo({ arg k, val;
        val.free;
      });
      recorder.keysValuesDo({ arg k, val;
        val.free;
      });
      players.keysValuesDo({ arg k, val;
        val.free;
      });
    }

  }

  

}
