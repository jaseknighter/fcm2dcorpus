// Engine_FCM2dCorpus

// Inherit methods from CroneEngine
Engine_FCM2dCorpus : CroneEngine {
  
  var osc_funcs;
  var recorder;
  var players;
  var eglut;
  var gslices;
  var max_slice_dur = 2;

	*new { arg context, doneCallback;
		^super.new(context, doneCallback);
	}


  alloc {
    var s = context.server;
    var compose, writebuf, append_slice, remove_slice, renumber_reduced_slice_array, recordlive, analyze, play_slice, lua_sender,sc_sender;
    var tree;
    var composewritebufdone, composelivewritebufdone, analyzewritebufdone;
    var current;    
    var indices = Buffer.new(s);
    var gslicebuf = Buffer.alloc(s, 1);
    var gslicesbuf = Buffer.alloc(s, 1);
    var gslicesbuf_temp = Buffer.alloc(s, 1);
    var gslices = Array.new();
    var point, previous;
    var src,normedBuf;
    var findices;
    var playing_slice=false;
    var audio_path="/home/we/dust/audio/fcm2dcorpus/";
      
 
    eglut = EGlut.new(s,context,this);
    osc_funcs = Dictionary.new();
    recorder = Dictionary.new();
    players = Dictionary.new();

    Routine({
      var gslicesbuf_path="/home/we/dust/audio/fcm2dcorpus/gslicesbufs.wav";
      File.delete(gslicesbuf_path);
    }).play;

    ["memsize",s.options.memSize].postln;
    s.options.memSize  = 8192*4; 
    ["memsize post",s.options.memSize].postln;
    lua_sender = NetAddr.new("127.0.0.1",10111);   
    sc_sender = NetAddr.new("127.0.0.1",57120);   
    lua_sender.sendMsg("/lua_fcm2dcorpus/sc_inited");
        
    

    composewritebufdone = {
      "compose writebufdone".postln;
      lua_sender.sendMsg("/lua_fcm2dcorpus/compose_written",audio_path ++ "temp/src.wav");
    };

    composelivewritebufdone = {
      "compose live writebufdone".postln;
      lua_sender.sendMsg("/lua_fcm2dcorpus/composelive_written",audio_path ++ "temp/live.wav");
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

    //  SynthDef("transport_player", { |xloc=0.5,yloc=0.5,src,index=0,rate=1,gate=1,env_trig_rate=2,startsamp,stopsamp,startsampL,stopsampL,windowSize = 512, hopSize = 256, fftSize = 512,tamp=1|
     SynthDef("transport_player", { |xloc=0.5,yloc=0.5,src,index=0,rate=1,gate=1,env_trig_rate=2,startsamp,stopsamp,startsampL,stopsampL,windowSize = 256, hopSize = 128, fftSize = 256,tamp=1|
      var ptrdelay=0.2;
      var phs = Phasor.ar(0,BufRateScale.kr(src)*rate,startsamp,stopsamp);
      var sig = BufRd.ar(1,src,phs);
      var dursecs = min((stopsamp - startsamp) / BufSampleRate.kr(src),2);
      var env,outenv;
      var rates=[-2,-1.5,-1.2,-1,-0.5,0.5,1,1.2,1.5,2];

      var phsAdd = LFNoise0.kr(5).range(0,dursecs*10*BufSampleRate.kr(src));
      var phsL = Phasor.ar(0,BufRateScale.kr(src)*rate,startsampL+phsAdd,stopsampL+phsAdd);
      
      var sigL = BufRd.ar(1,src,phsL);
      var dursecsL = min((stopsampL - startsampL) / BufSampleRate.ir(src),2);
      var transportSig,transportGrains;
      var tpan,tenv;
      var env_trig=Trig.kr(Impulse.kr(env_trig_rate));
              
      env = EnvGen.kr(Env([0,1,1,0],[0.05,dursecs-0.1,0.05]),gate:gate* env_trig);
      transportSig = FluidAudioTransport.ar(sig,sigL,xloc,windowSize,hopSize,fftSize);
      tpan = xloc.linlin(0,1,-1, 1);
      // tenv = EnvGen.kr(
      //   // Env([0, 1, 0], [(1/30)/2, (1/30)/2], \sin, 1),
      //   Env([0, 1, 0], [dursecs, 0], \sin, 1),
      //   gate,
      //   // levelScale: tamp,
      //   doneAction: 2
      // );

      FluidBufCompose.process(s, src, startsampL,stopsampL, startChan: 0, numChans: 1, gain: 1, destination: gslicebuf, destStartFrame: 0, destStartChan: 0, destGain: 0, freeWhenDone: true, action: {
        ["gslicebuf composed",gslicebuf].postln;
        // eglut.fillEGBufs(1,gslicebuf);
        // gslicebuf.write(gslicebuf_path,header_format,sample_format);            
        lua_sender.sendMsg("/lua_fcm2dcorpus/gslicebuf_composed");
      });



      transportSig=(transportSig.dup*env)*0.25*yloc;
      Out.ar([0,1],transportSig);
    }).add;

    s.sync;

     //play a slice
    play_slice = {
      arg index,startInt,durInt,len=2;
      {
        var startsamp = Index.kr(indices,index);
        var stopsamp = Index.kr(indices,index+1);
        var phs = Phasor.ar(0,BufRateScale.ir(src),startsamp,stopsamp);
        var sig = BufRd.ar(1,src,phs);
        var dursecs = (stopsamp - startsamp) / BufSampleRate.ir(src);
        var env;
        var ptr, bufrate;

        dursecs = min(dursecs,1);
        env = EnvGen.kr(Env([0,1,1,0],[0.05,dursecs-0.1,0.05]),doneAction:2);
        //
        // FluidBufCompose.process(server, source, startFrame: 0, numFrames: -1, startChan: 0, numChans: -1, gain: 1, destination, destStartFrame: 0, destStartChan: 0, destGain: 0, freeWhenDone: true, action)
        ["compose gslicebuf",s, src, gslicebuf].postln;
        Routine({          
          gslicebuf.free;
          s.sync;
          gslicebuf = Buffer.alloc(s, 1);
          s.sync;
          FluidBufCompose.process(s, src, startInt, durInt, startChan: 0, numChans: 1, gain: 1, destination: gslicebuf, destStartFrame: 0, destStartChan: 0, destGain: 0, freeWhenDone: true, action: {
            ["gslicebuf composed",gslicebuf].postln;
            // eglut.fillEGBufs(1,gslicebuf);
            // gslicebuf.write(gslicebuf_path,header_format,sample_format);            
            lua_sender.sendMsg("/lua_fcm2dcorpus/gslicebuf_composed");
          });
          s.sync;
          // eglut.readBuf(1,gslicebuf_path);
          
        }).play;


        //
        

        sig.dup * env * 0.125;
      }.play;
    };
    
    append_slice = {
      arg header_format="WAV", sample_format="int24";
      var gslicebuf_path=audio_path ++ "temp/gslicebuf.wav";
      var gslicesbuf_path=audio_path ++ "temp/gslicesbufs.wav";
      var start_frame;
      var end_frame;
      var deleted;
      ["append", gslicebuf.numFrames, gslicesbuf.numFrames,gslicesbuf].postln;
      if (gslices.size>0,{
        start_frame = gslices[gslices.size - 1][1] + 1;
      },{
        start_frame = 1;
      });
      end_frame = start_frame + gslicebuf.numFrames;
      [start_frame,end_frame].postln;
      gslices=gslices.add([start_frame, end_frame]);
      gslicebuf.normalize();
      gslicesbuf_temp = Buffer.new(s,1);
      FluidBufCompose.process(s, gslicesbuf, startFrame: 0, numFrames: start_frame, startChan: 0, numChans: 1, gain: 1, destination: gslicesbuf_temp, destStartFrame: 0, destStartChan: 0, destGain: 0, freeWhenDone: true, action: {
        FluidBufCompose.process(s, gslicebuf, startFrame: 0, numFrames: -1, startChan: 0, numChans: 1, gain: 1, destination: gslicesbuf_temp, destStartFrame: start_frame, destStartChan: 0, destGain: 0, freeWhenDone: true, action: {
          gslicesbuf = Buffer.new(s,1);
          FluidBufCompose.process(s, gslicesbuf_temp, destination: gslicesbuf, action: {
            ["gslicebuf appended",start_frame,end_frame,gslices,gslicesbuf,gslicebuf].postln;
            // eglut.fillEGBufs(1,gslicebuf);
            Routine({
              deleted = File.delete(gslicesbuf_path);   
              gslicebuf.write(gslicebuf_path,header_format,sample_format);            
              gslicesbuf.write(gslicesbuf_path,header_format,sample_format);            
              lua_sender.sendMsg("/lua_fcm2dcorpus/gslicebuf_appended",gslicesbuf_path);
            }).play;
          });
        });
      });
    };

    renumber_reduced_slice_array = {
      arg remove_ix;
      var slice_lengths = Array.new(gslices.size-1);
      ["rrsa start",gslices].postln;
      gslices.do({|key,i|
        if((i>remove_ix),{
          ["add",gslices[i][1],gslices[i][0],(gslices[i][1] - gslices[i][0])].postln;
          slice_lengths.add(gslices[i][1] - gslices[i][0]);
        })
      });
      ["slice_lengths",slice_lengths].postln;
      gslices.do({|key,i|
        if((i>=remove_ix).and(gslices[i+1]!=nil),{
          var last_finish;
           if (i>0,{
            last_finish = gslices[i-1][1];
           },{
            last_finish = 0;
           });
          gslices[i][0] = last_finish+1;
          gslices[i][1] = last_finish+1+slice_lengths[0];
          slice_lengths.removeAt(0);
        })
      });
      gslices.removeAt(gslices.size - 1);
      ["rrsa finish",gslices].postln;
    };

    remove_slice = {
      arg bufnum=1, slice_num=1,start_frame=1, end_frame=1,
      header_format="WAV", sample_format="int24";
      var gslicesbuf_path=audio_path ++ "temp/gslicesbufs.wav";
      var deleted;
      renumber_reduced_slice_array.(slice_num);
      "  ".postln;
      "  ".postln;
      "  ".postln;
      gslicesbuf_temp.zero;
            
      FluidBufCompose.process(s, gslicesbuf, startFrame: 0, numFrames: start_frame, startChan: 0, numChans: 1, gain: 1, destination: gslicesbuf_temp, destStartChan: 0, destGain: 0, freeWhenDone: true, action: {
        if (gslicesbuf.numFrames>end_frame,{
          FluidBufCompose.process(s, gslicesbuf, startFrame: end_frame+1, numFrames: -1, startChan: 0, numChans: 1, gain: 1, destination: gslicesbuf_temp, destStartChan: 0, destGain: 0, freeWhenDone: true, action: {
            FluidBufCompose.process(s, gslicesbuf_temp, startFrame: 0, numFrames: -1, startChan: 0, numChans: 1, gain: 1, destination: gslicesbuf, destStartChan: 0, destGain: 0, freeWhenDone: true, action: {
              ["gslicesbuf removed before and after",start_frame,gslicesbuf.numFrames,end_frame,gslicesbuf.numFrames>end_frame,gslices,gslicesbuf].postln;
              // gslicesbuf.zero;
              ["gslicesbuf nf before",gslicesbuf.numFrames].postln;
              // gslicesbuf.numFrames = gslicesbuf.numFrames + (start_frame-end_frame);
              gslicesbuf.numFrames = gslices[gslices.size-1][1];
              ["gslicesbuf nf after",gslicesbuf.numFrames].postln;
              Routine({
                deleted = File.delete(gslicesbuf_path);
                ["deleted? ", deleted].postln;
                gslicesbuf.write(gslicesbuf_path,header_format,sample_format,gslicesbuf.numFrames);            
                lua_sender.sendMsg("/lua_fcm2dcorpus/gslicebuf_appended",gslicesbuf_path);
              }).play;
            });
          });
        },{
          FluidBufCompose.process(s, gslicesbuf_temp, startFrame: 0, numFrames: -1, startChan: 0, numChans: 1, gain: 1, destination: gslicesbuf, destStartChan: 0, destGain: 0, freeWhenDone: true, action: {
            ["gslicesbuf removed before",start_frame,gslicesbuf.numFrames,end_frame,gslicesbuf.numFrames>end_frame,gslices,gslicesbuf].postln;
            // gslicesbuf.zero;
            ["gslicesbuf nf before",gslicesbuf.numFrames].postln;
            gslicesbuf.numFrames = gslices[gslices.size-1][1];
            ["gslicesbuf nf after",gslicesbuf.numFrames].postln;
            Routine({
              deleted = File.delete(gslicesbuf_path);
              ["deleted? ", deleted].postln;
              gslicesbuf.write(gslicesbuf_path,header_format,sample_format,gslicesbuf.numFrames);            
              lua_sender.sendMsg("/lua_fcm2dcorpus/gslicebuf_appended",gslicesbuf_path);
            }).play;
          });
        });
      });
    };
    

    compose = {
      arg folder_path, file_path;
      var loader;
      var src_path=audio_path ++ "temp/src.wav";
      var header_format="WAV";
      var sample_format="int24";
      var sample_length;
      src = Buffer.new(s);
      
      fork{
        "start loader".postln;
        if (folder_path != nil,{
          "load folder".postln;
          folder_path.postln;
          loader = FluidLoadFolder(folder_path).play(s);
          sample_length = loader.buffer.numFrames/loader.buffer.sampleRate/60;
          sample_length = min(sample_length,2);
          ["sample_length",sample_length].postln;
          // s.sync;
            ["loader loaded",].postln;
            "set mono src buffer".postln;
            if(loader.buffer.numChannels > 1){
              "stereo to mono".postln;
              src = Buffer.new(s);
              FluidBufCompose.process(s,loader.buffer,numFrames: loader.buffer.sampleRate*60*sample_length,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
                ("buf composed1").postln;
                FluidBufCompose.process(s,loader.buffer,numFrames: loader.buffer.sampleRate*60*sample_length,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                  ("buf composed2").postln;
                  ["audio composition completed",loader.buffer,loader.buffer.sampleRate*60*sample_length,src,src_path,header_format,sample_format].postln;
                  writebuf.(src,src_path,header_format,sample_format,"compose");
                });
              });
            }{
              "audio is already mono".postln;
              ["audio composition completed",loader.buffer,loader.buffer.sampleRate*60*2,src,src_path,header_format,sample_format].postln;
              writebuf.(src,src_path,header_format,sample_format,"compose");
            };
        },{
          ["load file",folder_path, file_path].postln;
          loader = Buffer.read(s,file_path);
          src = Buffer.new(s);
          s.sync;
          sample_length = loader.numFrames/loader.sampleRate/60;
          sample_length = min(sample_length,2);
          ["sample_length",sample_length].postln;
          ["file loaded",loader.numChannels,loader.numFrames,loader.sampleRate].postln;
          if(loader.numChannels > 1){
            "stereo to mono".postln;
            FluidBufCompose.process(s,loader,numFrames: loader.sampleRate*60*sample_length,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
              ("buf composed1").postln;
              FluidBufCompose.process(s,loader,numFrames: loader.sampleRate*60*sample_length,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                ("buf composed2").postln;
                ["audio composition completed",src.numChannels,src.numFrames,src.sampleRate].postln;
                writebuf.(src,src_path,header_format,sample_format,"compose");
              });
            });
          }{
            "audio is already mono".postln;
            src = loader;
            writebuf.(src,src_path,header_format,sample_format,"compose");
            ["mono audio composition completed",src.numChannels,src.numFrames,src.sampleRate].postln;
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

              FluidBufMFCC.process(s,src,start,num,features:mfccs,numCoeffs:13,startCoeff:1,action:{
                FluidBufStats.process(s,mfccs,stats:stats,select:[\mean],action:{
                  FluidBufFlatten.process(s,stats,destination:flat,action:{
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

                },{
                  deststarts.insert(i,0);
                  FluidBufCompose.process(s,src,startFrame:start_frame,numFrames: duration,destination:normedBuf,destStartFrame:0, action:{});
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

    // osc_funcs.put("write_src",
    //   OSCFunc.new({ |msg, time, addr, recvPort|
    //     var path=msg[1];
    //     var header_format="WAV";
    //     var sample_format="int24";
    //     ["write source",src,path,header_format,sample_format].postln;
    //     writebuf.(src,path,header_format,sample_format,"analyze");
    //   }, "/sc_fcm2dcorpus/write_src");
    // );
    
    osc_funcs.put("write_normed",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var path=msg[1];
        var header_format="WAV";
        var sample_format="int24";
        ["write normed",normedBuf,path,header_format,sample_format].postln;
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
        var path=audio_path ++ "temp/live.wav";
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

    osc_funcs.put("play_slice",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var x=msg[1];
        var y=msg[2];
        point.setn(0,[x,y]);
        tree.kNearest(point,1,{
          arg nearest;
          var start, stop, dur;
          if(nearest != previous){
            start = findices[nearest.asInteger];
            stop = findices[nearest.asInteger+1];
            dur = stop-start;
            ["start,dur", start,stop,dur].postln;
            play_slice.(nearest.asInteger,start,dur);
            // previous = nearest;
            current=nearest;
            lua_sender.sendMsg("/lua_fcm2dcorpus/slice_played",current,previous);
            previous=nearest;
          }
        });
      }, "/sc_fcm2dcorpus/play_slice");
    );
    
    osc_funcs.put("append_slice",
      OSCFunc.new({ |msg, time, addr, recvPort|
        append_slice.();
      }, "/sc_fcm2dcorpus/append_slice");
    );
    
    osc_funcs.put("remove_selected_gslice",
      OSCFunc.new({ |msg, time, addr, recvPort|
        ["remove_selected_gslice",msg].postln;
        remove_slice.(bufnum:msg[1],slice_num:msg[2].asInteger,start_frame:msg[3].asInteger,end_frame:msg[4].asInteger);
      }, "/sc_fcm2dcorpus/remove_selected_gslice");
    );

    osc_funcs.put("get_gslices",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var slice_points="";
        gslices.do({arg slice,i; 
          slice.do({arg slice_point,j;
            if((i==0).and(j==0),{
              slice_points=slice_point.asString;
            },{
              slice_points=slice_points ++ "," ++ slice_point.asString;
            }) 
          });
          ["slice_points",slice_points].postln;
          lua_sender.sendMsg("/lua_fcm2dcorpus/set_gslices",slice_points);
        });
      }, "/sc_fcm2dcorpus/get_gslices");
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

    osc_funcs.put("transport_gate",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var gate = msg[1];
        ["transport_gate",gate].postln;
        players.at(\currentPlayer).set(\gate,gate);
      }, "/sc_fcm2dcorpus/transport_gate");
    );

    osc_funcs.put("transport_slice_x_y",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var x=msg[1];
        var y=msg[2];
        players.at(\currentPlayer).set(\xloc,x);
        players.at(\currentPlayer).set(\yloc,y);
      }, "/sc_fcm2dcorpus/transport_slice_x_y");
    );
  }

  free {
    "free FCM2dCorpus".postln;  
    eglut.free();
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
