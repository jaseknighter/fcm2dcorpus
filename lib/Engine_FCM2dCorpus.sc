// Engine_FCM2dCorpus

// Inherit methods from CroneEngine
Engine_FCM2dCorpus : CroneEngine {
  var point, previous;
  var src;
  var oscs;
  var recorder;
  var osccaller=0;


	*new { arg context, doneCallback;
		^super.new(context, doneCallback);
	}

  alloc {
    var s = context.server;
    var compose, writebuf, recordlive, analyze, play_slice, lua_sender,sc_sender;
    var indices, tree;
    var composewritebufdone, composelivewritebufdone, analyzewritebufdone;
    var normedBuf;

    oscs = Dictionary.new();
    recorder = Dictionary.new();
    
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
      var path=Platform.defaultTempDir+/+"src.wav";
      var header_format="WAV";
      var sample_format="int24";
      src = Buffer.new(s);
      
      fork{
        "start loader".postln;
        if (path != nil,{
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
                  writebuf.(src,path,header_format,sample_format,"compose");
                });
              });
            }{
              "audio is already mono".postln;
              ("audio composition completed").postln;
              // src.write(Platform.defaultTempDir+/+"src.wav", "WAV", 'int16', completionMessage:{lua_sender.sendMsg("/lua_fcm2dcorpus/src_written",Platform.defaultTempDir+/+"src.wav")});
              writebuf.(src,path,header_format,sample_format,"compose");
            };
        },{
          ["load file",folder_path, file_path].postln;
          loader = Buffer.read(s,file_path);
          s.sync;
          ["file loaded",loader.numChannels,src.numFrames,src.sampleRate].postln;
          if(loader.numChannels > 1){
            "stereo to mono".postln;
            FluidBufCompose.processBlocking(s,loader,numFrames: loader.sampleRate*60*2,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
              ("buf composed1").postln;
              FluidBufCompose.processBlocking(s,loader,numFrames: loader.sampleRate*60*2,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                ("buf composed2").postln;
                ("audio composition completed").postln;
                // src.write(Platform.defaultTempDir+/+"src.wav", "WAV", 'int16', completionMessage:{lua_sender.sendMsg("/lua_fcm2dcorpus/src_written",Platform.defaultTempDir+/+"src.wav")});
                writebuf.(src,path,header_format,sample_format,"compose");
              });
            });
          }{
            "audio is already mono".postln;
            // src.write(Platform.defaultTempDir+/+"src.wav", "WAV", 'int16', completionMessage:{lua_sender.sendMsg("/lua_fcm2dcorpus/src_written",Platform.defaultTempDir+/+"src.wav")});
            ("mono audio composition completed").postln;
            writebuf.(src,path,header_format,sample_format,"compose");
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

            ["fa"].postln;
            fa.doAdjacentPairs{
              arg start, end, i;
              var num = end - start;

              FluidBufMFCC.processBlocking(s,src,start,num,features:mfccs,numCoeffs:13,startCoeff:1,action:{
                FluidBufStats.processBlocking(s,mfccs,stats:stats,select:[\mean],action:{
                  FluidBufFlatten.processBlocking(s,stats,destination:flat,action:{});
                });
              });

              analyses.addPoint(i,flat);
              if((i)==fa.size){
                 lua_sender.sendMsg("/lua_fcm2dcorpus/analysis_progress",i,fa.size);
              };
              if((i%50)==49){
                "slice % / %".format(i,fa.size).postln;
                "slice % / %".format(i,fa.size).postln;
                "slice % / %".format(i,fa.size).postln;
                "slice % / %".format(i,fa.size).postln;
                // s.sync;
                lua_sender.sendMsg("/lua_fcm2dcorpus/analysis_progress",i,fa.size);
              };
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
            umapped.print;
            normed = FluidDataSet(s);
            FluidNormalize(s).fitTransform(umapped,normed);

            "normed".postln;
            normed.postln;

            tree = FluidKDTree(s, numNeighbours:1, radius:0.5).fit(normed);
            ["tree set",tree].postln;
            normed.write(Platform.defaultTempDir+/+"temp_dataset.json");
            "normed json file generated".postln;
            [Platform.defaultTempDir+/+"temp_dataset.json"].postln;
            // Routine({                      
            //   1.wait;
            // }).play;
            // // normed_dict.postln;

            s.sync;
            "start normed dump".postln;
            ////////////////////////
            normed.dump({ arg dict;
              var dictkeys;
              var previous = nil;
              var deststarts=List.new();
              var durations=List.new();
              normedBuf = Buffer(s);
              //save the buffer in the order of the dict
              dictkeys=dict.values[0].keys;
              dictkeys=Array.newFrom(dictkeys);
              ["indices.loadToFloatArray",dictkeys].postln;
              indices.loadToFloatArray(action:{
                arg farr;
                farr.doAdjacentPairs{
                  arg start, end, i;
                  var num = end - start;
                  durations.add(num);
                };
                s.sync;
                "durations captured".postln;
                dictkeys.do({|key,i|
                  var startsamp,stopsamp, start_frame, duration, dest_start, lastduration=0, laststart;
                  start_frame = farr[key.asInteger];
                  duration = durations[key.asInteger];
                  if(i>0,{
                    lastduration = durations[dictkeys[i-1].asInteger];
                    laststart = deststarts[i-1];
                    dest_start = lastduration+laststart;
                    deststarts.insert(i,dest_start);
                    // ([key,i,lastduration, laststart, deststarts,farr[key.asInteger]]).postln;
                    FluidBufCompose.processBlocking(s,src,startFrame:start_frame,numFrames: duration,destination:normedBuf,destStartFrame:dest_start, action:{});
                    // FluidBufCompose.processBlocking(s,src,startFrame:start_frame,numFrames: duration,destination:normedBuf,destStartFrame:dest_start, action:{["composed",i].postln});

                  },{
                    deststarts.insert(i,0);
                    FluidBufCompose.processBlocking(s,src,startFrame:start_frame,numFrames: duration,destination:normedBuf,destStartFrame:0, action:{});
                    // FluidBufCompose.processBlocking(s,src,startFrame:start_frame,numFrames: duration,destination:normedBuf,destStartFrame:0, action:{["composed",i].postln});
                  });
                });
              });
            });

            /////////////////////////
            "analysis all done".postln;
            lua_sender.sendMsg("/lua_fcm2dcorpus/analysis_dumped");
          });
        });
      };
    };

    oscs.put("set_2dcorpus",
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

    oscs.put("write_src",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var path=msg[1];
        var header_format="WAV";
        var sample_format="int24";
        ["write_source",src,path,header_format,sample_format].postln;
        writebuf.(src,path,header_format,sample_format,"analyze");
      }, "/sc_fcm2dcorpus/write_src");
    );
    
    oscs.put("write_normed",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var path=msg[1];
        var header_format="WAV";
        var sample_format="int24";
        ["write_src",normedBuf,path,header_format,sample_format].postln;
        writebuf.(normedBuf,path,header_format,sample_format,"analyze");
      }, "/sc_fcm2dcorpus/write_normed");
    );

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
        // ["src.first.bufnum",src.first.bufnum].postln;
        dursecs = min(dursecs,1);
        env = EnvGen.kr(Env([0,1,1,0],[0.03,dursecs-0.06,0.03]),doneAction:2);
        sig.dup * env;
      }.play;
    };

    oscs.put("analyze_2dcorpus",
      OSCFunc.new({ |msg, time, addr, recvPort|
        analyze.();
      }, "/sc_fcm2dcorpus/analyze_2dcorpus");
    );

    oscs.put("record_live",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var secs=msg[1];
        var path=Platform.defaultTempDir+/+"live.wav";
        var header_format="WAV";
        var sample_format="int24";
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

    oscs.put("play_slice",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var x=msg[1];
        var y=msg[2];
        point.setn(0,[x,y]);
        // [point,x,y,tree,tree.kNearest].postln;
        tree.kNearest(point,1,{
          arg nearest;
          // [nearest, previous,nearest != previous].postln;
          if(nearest != previous){
            play_slice.(nearest.asInteger);
            lua_sender.sendMsg("/lua_fcm2dcorpus/slice_played",nearest);
            previous = nearest;
          }
        });
      }, "/sc_fcm2dcorpus/play_slice");
    );

  }

  
  free {
    "free FCM2dCorpus".postln;  
    oscs.free;  
    // twoD_instrument.free;
  }

}
