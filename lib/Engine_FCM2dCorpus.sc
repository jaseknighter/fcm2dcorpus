// Engine_FCM2dCorpus

// Inherit methods from CroneEngine
Engine_FCM2dCorpus : CroneEngine {
  
  var buses;
  var osc_funcs;
  var recorders;
  var players;
  var eglut;
  var gslices;
  var max_slice_dur=2;

	*new { arg context, doneCallback;
		^super.new(context, doneCallback);
	}


  alloc {
    var s=context.server;
    var compose, writebuf, append_gslice, remove_slice, renumber_reduced_slice_array, analyze, play_slice, lua_sender,sc_sender;
    var tree;
    var composewritebufdone, composelivewritebufdone, analyzewritebufdone, transportwritebufdone;
    var indices=Buffer.new(s);
    var gslicebuf=Buffer.alloc(s, 1);
    var gslicesbuf=Buffer.alloc(s, 1);
    var gslicesbuf_temp=Buffer.alloc(s, 1);
    var gslices=Array.new();
    var point, current_slice, previous_slice;
    var src,normedBuf;
    var srcSampleRate;
    var findices;
    var playing_slice=false;
    var audio_path="/home/we/dust/audio/fcm2dcorpus/";
    var transport_volume=1;
    var transport_rate=1;
    var transport_trig_rate=4;
    var transport_sig_pos=0;
    var transport_reset_pos=0;
    var transport_stretch=0;
    var transportRecorder;
    var transportBuffer=Buffer.alloc(s,48000*60*0.5);
    // var zeroBuffer=Buffer.alloc(s, 48000*5);
    var buf_recording=0;
    var buf_writing=0;
    var x_loc;
    var ssample_rate=48000;


    eglut=EGlut.new(s,context,this);

    buses = Dictionary.new();
    osc_funcs=Dictionary.new();
    recorders=Dictionary.new();
    players=Dictionary.new();

    Routine({
      var gslicesbuf_path="/home/we/dust/audio/fcm2dcorpus/gslicesbufs.wav";
      File.delete(gslicesbuf_path);
    }).play;

    ["memsize",s.options.memSize].postln;
    s.options.memSize =8192*4; 
    ["memsize post",s.options.memSize].postln;
    lua_sender=NetAddr.new("127.0.0.1",10111);   
    sc_sender=NetAddr.new("127.0.0.1",57120);   
    lua_sender.sendMsg("/lua_fcm2dcorpus/sc_inited");
        
    // buses.put("busTransport",Bus.audio(s,1));


    composewritebufdone={
      buf_writing=0;
      src.query({ | msgType, bufnum, numFrames, numChannels, sampleRate |
        srcSampleRate=sampleRate;
        ["compose writebufdone",srcSampleRate].postln;
        lua_sender.sendMsg("/lua_fcm2dcorpus/compose_written",audio_path ++ "temp/src.wav");
      });
    };

    composelivewritebufdone={
      buf_writing=0;
      src.query({ | msgType, bufnum, numFrames, numChannels, sampleRate |
        srcSampleRate=sampleRate;
        ["compose live writebufdone",srcSampleRate].postln;
        lua_sender.sendMsg("/lua_fcm2dcorpus/composelive_written",audio_path ++ "temp/live.wav");
      });
    };

    analyzewritebufdone={
      buf_writing=0;
      "analyze writebufdone".postln;
      lua_sender.sendMsg("/lua_fcm2dcorpus/analyze_written");
    };
    
    transportwritebufdone={
      buf_writing=0;
      "transport writebufdone".postln;
      lua_sender.sendMsg("/lua_fcm2dcorpus/transportslice_composed",audio_path ++ "temp/transport.wav");
    };

    writebuf={
      arg buf, path, header_format, sample_format,numFrames,msg;
      // if ((buf_writing==0), {
      ["starting writebuf",buf.bufnum,buf_writing,buf_recording].postln;
      
      if (buf_writing==0, {
        Routine({
          
          buf.normalize();
          s.sync;
          buf_writing=1;
          if (msg == "compose",{
            buf.write(path, header_format, sample_format, numFrames, completionMessage:composewritebufdone);
          });
          if (msg == "composelive",{
            "write live recording".postln;
            buf.write(path, header_format, sample_format,numFrames,completionMessage:composelivewritebufdone);
          });
          if (msg == "analyze",{
            buf.write(path, header_format, sample_format,numFrames,completionMessage:analyzewritebufdone);
          });
          if (msg == "transport",{
            buf.write(path, header_format, sample_format,numFrames,completionMessage:transportwritebufdone);
          });
        }).play;
      },{
        "buf writing already, please wait".postln;
      });
    };

    SynthDef("live_recorder", { 
      arg buf, rate=1, secs=10.0;
      var dur=s.sampleRate * secs;
      var in=SoundIn.ar(0);
      RecordBuf.ar(in,buf, loop:0, doneAction:2);
      0.0 //quiet
    }).add;

     SynthDef("transport_player", { 
      arg xloc=0.5,yloc=0.5,src,index=0,transport_rate=1,gate=1,
          transport_trig_rate=4,
          startsamp,stopsamp,startsamp_prev,stopsamp_prev,
          windowSize=256, hopSize=128, fftSize=256,tamp=1,
          reset_pos=0,stretch=0,buf_recording=0;
      var out;
      var phs,dursecs,dursecs_prev;
      var sig,env,outenv;
      var tenv,trig_rate, phasor_trig, env_trig;
      var reset_posL, phsL, sigL;
      var reset_phs, reset_phsL;
      var transportSig=0;
      var transportGrains;
      var transport_startsamp;
      var transport_stopsamp;
      var recordbuf_offset;

      x_loc=xloc;
      // startsamp_prev
      stopsamp=stopsamp+(stretch*BufSampleRate.kr(src));
      stopsamp_prev=stopsamp_prev+(stretch*BufSampleRate.kr(src));
      dursecs=(stopsamp - startsamp) / BufSampleRate.kr(src);
      dursecs_prev=(stopsamp_prev - startsamp_prev) / BufSampleRate.kr(src);
      dursecs = LinLin.kr(x_loc,0,1,dursecs,dursecs_prev);

      // dursecs=min((((stopsamp) - startsamp) / BufSampleRate.kr(src))+stretch,2+stretch);
      // var rates=[-2,-1.5,-1.2,-1,-0.5,0.5,1,1.2,1.5,2];

      trig_rate=(transport_trig_rate)*(1-(buf_recording))+(dursecs.reciprocal*(buf_recording));
      // trig_rate=(Lag.kr(transport_trig_rate)*(1-(buf_recording)))+(dursecs.reciprocal*(buf_recording));
      phasor_trig=Impulse.kr(trig_rate);
      env_trig=Impulse.kr(trig_rate);
      // env_trig=Trig.kr(Impulse.kr(trig_rate));
      // [buf_recording,trig_rate].poll;
      // var phsAdd=LFNoise0.kr(5).range(0,dursecs*10*BufSampleRate.kr(src));
      env=EnvGen.kr(Env([0,1,1,0],[0.05,dursecs-1,0.05]),gate:gate* env_trig);
      
      reset_pos=startsamp + (reset_pos*(stopsamp - startsamp));      
      reset_phs=Phasor.ar(phasor_trig,transport_rate,startsamp,stopsamp,reset_pos);
      phs=Phasor.ar(Impulse.kr(100),transport_rate,startsamp,stopsamp,reset_phs);
      sig=BufRd.ar(1,src,phs);

      reset_posL=startsamp_prev + (reset_pos*(stopsamp_prev - startsamp_prev));
      reset_phsL=Phasor.ar(Impulse.kr(100),transport_rate,startsamp_prev,stopsamp_prev,reset_posL);
      phsL=Phasor.ar(phasor_trig,transport_rate,startsamp_prev,stopsamp_prev,reset_phsL);
      sigL=BufRd.ar(1,src,phsL);

      transportSig=FluidAudioTransport.ar(sig,sigL,xloc,windowSize,hopSize,fftSize);
      
      
      // recordbuf_offset=LinLin.kr(xloc,0,1,phs,phsL);
      recordbuf_offset=LinLin.kr(phs,startsamp,stopsamp,0,stopsamp-startsamp);
      RecordBuf.ar(transportSig, transportBuffer, offset: recordbuf_offset, run: buf_recording, loop:1, trigger: Impulse.kr(100)*(buf_recording));
      // RecordBuf.ar(transportSig, transportBuffer, offset: recordbuf_offset, run: 1-buf_recording, loop:1, trigger: Impulse.kr(100));
      // RecordBuf.ar(transportSig, transportBuffer, loop:1);


      transport_sig_pos=LinLin.kr(phs,startsamp,stopsamp,0,1);

      SendReply.kr(Impulse.kr(15), "/sc_fcm2dcorpus/transport_sig_pos",transport_sig_pos.value);

      Out.ar([0,1],[transportSig,transportSig]*tamp*gate);
      // Out.ar([0,1],out);
    }).add;

    s.sync;

     //play a slice
    play_slice={
      arg index,startInt,durInt,volume=1;
      {
        var startsamp=Index.kr(indices,index);
        var stopsamp=Index.kr(indices,index+1);
        var phs=Phasor.ar(0,BufRateScale.kr(src),startsamp,stopsamp);
        var sig=BufRd.ar(1,src,phs)*volume*2;
        var dursecs=(stopsamp - startsamp) / BufSampleRate.kr(src);
        var env;
        var ptr, bufrate;

        dursecs=min(dursecs,1);
        env=EnvGen.kr(Env([0,1,1,0],[0.05,dursecs-0.1,0.05]),doneAction:2);
        // ["compose gslicebuf",s, src, gslicebuf].postln;
        Routine({          
          FluidBufCompose.process(s, src, startInt, durInt, startChan: 0, numChans: 1, gain: 1, destination: gslicebuf, destStartFrame: 0, destStartChan: 0, destGain: 0, freeWhenDone: true, action: {
            // ["gslicebuf composed",gslicebuf].postln;
            lua_sender.sendMsg("/lua_fcm2dcorpus/gslicebuf_composed");
          });
        }).play;


        //
        

        sig.dup * env * 0.125;
      }.play;
    };
    
    append_gslice={
      arg header_format="WAV", sample_format="int24";
      var gslicebuf_path=audio_path ++ "temp/gslicebuf.wav";
      var gslicesbuf_path=audio_path ++ "temp/gslicesbufs.wav";
      var start_frame;
      var end_frame;
      var deleted;
      ["append", gslicebuf.numFrames, gslicesbuf.numFrames,gslicesbuf].postln;
      if (gslices.size>0,{
        start_frame=gslices[gslices.size - 1][1] + 1;
      },{
        start_frame=1;
      });
      end_frame=start_frame + gslicebuf.numFrames;
      [start_frame,end_frame].postln;
      gslices=gslices.add([start_frame, end_frame]);
      gslicebuf.normalize();
      gslicesbuf_temp=Buffer.new(s,1);
      FluidBufCompose.process(s, gslicesbuf, startFrame: 0, numFrames: start_frame, startChan: 0, numChans: 1, gain: 1, destination: gslicesbuf_temp, destStartFrame: 0, destStartChan: 0, destGain: 0, freeWhenDone: true, action: {
        FluidBufCompose.process(s, gslicebuf, startFrame: 0, numFrames: -1, startChan: 0, numChans: 1, gain: 1, destination: gslicesbuf_temp, destStartFrame: start_frame, destStartChan: 0, destGain: 0, freeWhenDone: true, action: {
          gslicesbuf=Buffer.new(s,1);
          FluidBufCompose.process(s, gslicesbuf_temp, destination: gslicesbuf, action: {
            ["gslicebuf appended",start_frame,end_frame,gslices,gslicesbuf,gslicebuf].postln;
            // eglut.fillEGBufs(1,gslicebuf);
            Routine({
              deleted=File.delete(gslicesbuf_path);   
              gslicebuf.write(gslicebuf_path,header_format,sample_format);            
              gslicesbuf.write(gslicesbuf_path,header_format,sample_format);            
              lua_sender.sendMsg("/lua_fcm2dcorpus/gslicebuf_appended",gslicesbuf_path);
            }).play;
          });
        });
      });
    };

    renumber_reduced_slice_array={
      arg remove_ix;
      var slice_lengths=Array.new(gslices.size-1);
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
            last_finish=gslices[i-1][1];
           },{
            last_finish=0;
           });
          gslices[i][0]=last_finish+1;
          gslices[i][1]=last_finish+1+slice_lengths[0];
          slice_lengths.removeAt(0);
        })
      });
      gslices.removeAt(gslices.size - 1);
      ["rrsa finish",gslices].postln;
    };

    remove_slice={
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
              // gslicesbuf.numFrames=gslicesbuf.numFrames + (start_frame-end_frame);
              gslicesbuf.numFrames=gslices[gslices.size-1][1];
              ["gslicesbuf nf after",gslicesbuf.numFrames].postln;
              Routine({
                deleted=File.delete(gslicesbuf_path);
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
            gslicesbuf.numFrames=gslices[gslices.size-1][1];
            ["gslicesbuf nf after",gslicesbuf.numFrames].postln;
            Routine({
              deleted=File.delete(gslicesbuf_path);
              ["deleted? ", deleted].postln;
              gslicesbuf.write(gslicesbuf_path,header_format,sample_format,gslicesbuf.numFrames);            
              lua_sender.sendMsg("/lua_fcm2dcorpus/gslicebuf_appended",gslicesbuf_path);
            }).play;
          });
        });
      });
    };
    

    compose={
      arg folder_path, file_path, max_sample_length=2;
      var loader;
      var src_path=audio_path ++ "temp/src.wav";
      var header_format="WAV";
      var sample_format="int24";
      var sample_length;
      src=Buffer.new(s);
      
      fork{
        "start loader".postln;
        if (folder_path != nil,{
          "load folder".postln;
          folder_path.postln;
          loader=FluidLoadFolder(folder_path).play(s);
          sample_length=loader.buffer.numFrames/loader.buffer.sampleRate/60;
          sample_length=min(sample_length,max_sample_length);
          ["sample_length",sample_length].postln;
          // s.sync;
            ["loader loaded",].postln;
            "set mono src buffer".postln;
            if(loader.buffer.numChannels > 1){
              "stereo to mono".postln;
              // src=Buffer.new(s);
              FluidBufCompose.processBlocking(s,loader.buffer,numFrames: loader.buffer.sampleRate*60*sample_length,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
                ("buf composed1").postln;
                FluidBufCompose.processBlocking(s,loader.buffer,numFrames: loader.buffer.sampleRate*60*sample_length,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                  ("buf composed2").postln;
                  ["audio composition completed",loader.buffer,loader.buffer.sampleRate*60*sample_length,src,src_path,header_format,sample_format].postln;
                  writebuf.(src,src_path,header_format,sample_format,-1,"compose");
                });
              });
            }{
              "audio is already mono".postln;
              ["audio composition completed",loader.buffer,loader.buffer.sampleRate*60*2,src,src_path,header_format,sample_format].postln;
              writebuf.(src,src_path,header_format,sample_format,-1,"compose");
            };
        },{
          ["load file",folder_path, file_path].postln;
          loader=Buffer.read(s,file_path);
          // src=Buffer.new(s);
          s.sync;
          sample_length=loader.numFrames/loader.sampleRate/60;
          sample_length=min(sample_length,max_sample_length);
          ["sample_length",sample_length].postln;
          ["file loaded",loader.numChannels,loader.numFrames,loader.sampleRate].postln;
          if(loader.numChannels > 1){
            "stereo to mono".postln;
            FluidBufCompose.process(s,loader,numFrames: loader.sampleRate*60*sample_length,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
              ("buf composed1").postln;
              FluidBufCompose.process(s,loader,numFrames: loader.sampleRate*60*sample_length,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                ("buf composed2").postln;
                ["audio composition completed",src.numChannels,src.numFrames,src.sampleRate].postln;
                writebuf.(src,src_path,header_format,sample_format,-1,"compose");
              });
            });
          }{
            "audio is already mono".postln;
            src=loader;
            writebuf.(src,src_path,header_format,sample_format,-1,"compose");
            ["mono audio composition completed",src.numChannels,src.numFrames,src.sampleRate].postln;
          };
          // });
        });
      };
    };

    analyze={
      arg sliceThresh=0.5, min_slice_length=2;
      var analyses, normed, umapped, normed_dict;
  
      indices=Buffer.new(s);
      point=Buffer.alloc(s,2);
      fork{
        ["slice and analyze",indices].postln;
        // s.sync;

        src.postln;
        0.5.wait;
        "start FluidBufOnsetSlice".postln;
        FluidBufOnsetSlice.process(s,src,numChans: 1,metric:0,threshold:sliceThresh,minSliceLength:min_slice_length,indices:indices,windowSize:1024,hopSize: 1024*2, action:{
        // FluidBufOnsetSlice.process(s,src,numChans: 1,metric:0,threshold:sliceThresh,indices:indices,action:{
          "FluidBufOnsetSlice done".postln;
          "average seconds per slice: %".format(src.duration / indices.numFrames).postln;
          // analysis
          analyses=FluidDataSet(s).clear;
          ["start analysis"].postln;
          indices.loadToFloatArray(action:{
            arg fa;
            var umap_iterations=10;
            var umap_num_neighbors=15;
            var mfccs=Buffer(s);
            var stats=Buffer(s);
            var flat=Buffer(s);
            findices=fa;
            ["fa size", fa.size].postln;
            
            fa.doAdjacentPairs{
              arg start, end, i;
              var num=end - start;

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
            umapped=FluidDataSet(s);
            if (fa.size < umap_num_neighbors,{ umap_num_neighbors=(fa.size/2).floor });
            "umapped created".postln;
            FluidUMAP(s,numDimensions:2,numNeighbours:umap_num_neighbors,minDist:0.9,iterations:umap_iterations).fitTransform(analyses,umapped);
            "umap done".postln;
            // umapped.print;
            normed=FluidDataSet(s);
            FluidNormalize(s).fitTransform(umapped,normed);

            "normed".postln;
            normed.postln;

            tree=FluidKDTree(s, numNeighbours:1, radius:0.5).fit(normed);
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
              normedBuf=Buffer(s);
              //save the buffer in the order of the dict
              dictkeys=dict.values[0].keys;
              dictkeys=Array.newFrom(dictkeys);
              ["indices.loadToFloatArray",dictkeys].postln;
              fa.doAdjacentPairs{
                arg start, end, i;
                var num=end - start;
                durations.add(num);
              };
              // s.sync;
              "durations captured".postln;
              dictkeys.do({|key,i|
                var startsamp,stopsamp, start_frame, duration, dest_start, lastduration=0, laststart;
                start_frame=fa[key.asInteger];
                duration=durations[key.asInteger];
                if(i>0,{
                  lastduration=durations[dictkeys[i-1].asInteger];
                  laststart=deststarts[i-1];
                  dest_start=lastduration+laststart;
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
        var path=msg[1].asString;
        var path_type=msg[2].asString;
        var max_sample_length=msg[3].asInteger;
        // var folder_path="/home/we/dust/code/fcm2dcorpus/lib/audio/";
        if (path_type == "folder",{
          (["call compose: folder",path,max_sample_length]).postln;
          compose.(path,nil,max_sample_length);
        },{
          (["call compose: file",path,max_sample_length]).postln;
          compose.(nil,path,max_sample_length);
        });
      }, "/sc_fcm2dcorpus/set_2dcorpus");
    );

    osc_funcs.put("write_normed",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var path=msg[1];
        var header_format="WAV";
        var sample_format="int24";
        ["write normed",normedBuf,path,header_format,sample_format].postln;
        writebuf.(normedBuf,path,header_format,sample_format,-1,"analyze");
      }, "/sc_fcm2dcorpus/write_normed");
    );

    osc_funcs.put("analyze_2dcorpus",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var threshold=msg[1];
        var min_slice_length=msg[2];
        analyze.(threshold,min_slice_length);
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

        src=Buffer.alloc(s, s.sampleRate * secs, 1);

        recorders.add(\live_recorder ->
          Synth(\live_recorder,[\secs,secs,\buf,src]);
        );

        
        Routine{
          secs.wait;
          ["recording completed",src].postln;
          writebuf.(src,path,header_format,sample_format,-1,"composelive");
        }.play;

      }, "/sc_fcm2dcorpus/record_live");
    );

    osc_funcs.put("play_slice",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var x=msg[1];
        var y=msg[2];
        var volume=msg[3];
        point.setn(0,[x,y]);
        tree.kNearest(point,1,{
          arg nearest_slice;
          var start, stop, dur;
          if(nearest_slice != previous_slice){
            start=findices[nearest_slice.asInteger];
            stop=findices[nearest_slice.asInteger+1];
            dur=stop-start;
            // ["start,dur", start,stop,dur].postln;
            play_slice.(nearest_slice.asInteger,start,dur,volume);
            current_slice=nearest_slice;
            lua_sender.sendMsg("/lua_fcm2dcorpus/slice_played",current_slice,previous_slice);
            // previous_slice=nearest_slice;
          }
        });
      }, "/sc_fcm2dcorpus/play_slice");
    );
    
    osc_funcs.put("append_gslice",
      OSCFunc.new({ |msg, time, addr, recvPort|
        append_gslice.();
      }, "/sc_fcm2dcorpus/append_gslice");
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


    osc_funcs.put("transport_slices",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var x=msg[1];
        var y=msg[2];
        
        point.setn(0,[x,y]);
        //note: error may be related to: https://discourse.flucoma.org/t/kNearest-issue/1508/5
        tree.kNearest(point,1,action:{
          arg nearest_slice;
          var startsamp,stopsamp;
    			var startsamp_prev,stopsamp_prev;
          var transportNumFrames,transportDuration;
          if(playing_slice == false,{
            Routine({
              if (players.at(\currentPlayer)!=nil,{
                players.at(\currentPlayer).postln;
                ["transport slice nearest_slice/current_slice1", nearest_slice,current_slice,previous_slice].postln;
                startsamp_prev=findices[previous_slice.asInteger];
                stopsamp_prev=findices[previous_slice.asInteger+1];
                players.at(\currentPlayer).set(\gate,0);
                players.at(\currentPlayer).free;
              },{
                if(previous_slice!=nil,{
                  ["transport slice nearest_slice/current_slice2",nearest_slice,current_slice,previous_slice].postln;
                  startsamp_prev=findices[previous_slice.asInteger];
                  stopsamp_prev=findices[previous_slice.asInteger+1];
                },{
                startsamp_prev=findices[nearest_slice.asInteger];
                stopsamp_prev=findices[nearest_slice.asInteger+1];
                });
              });
              startsamp=findices[nearest_slice.asInteger];
              stopsamp=findices[nearest_slice.asInteger+1];

              // players.add(\nearest_slicePlayer->Synth(
              players.add(\currentPlayer->Synth(
                \transport_player,[
                  \xloc,x,
                  \yloc,y,
                  \src,src,
                  \index, nearest_slice.asInteger,
                  \startsamp,startsamp,
                  \stopsamp,stopsamp,
                  \startsamp_prev,startsamp_prev,
                  \stopsamp_prev,stopsamp_prev,
                  \tamp,transport_volume,
                  \transport_rate,0,
                  \transport_trig_rate,transport_trig_rate,
                  \stretch,transport_stretch,
                  \reset_pos,transport_reset_pos
                ]
              ));
            
              playing_slice=true;
              lua_sender.sendMsg("/lua_fcm2dcorpus/slice_played",nearest_slice,current_slice);
              // current_slice=nearest_slice;
              previous_slice=current_slice;
              // src.query({ | msgType, bufnum, numFrames, numChannels, sampleRate |

              ["record transport sig",srcSampleRate].postln;

              transportNumFrames=(((stopsamp+(transport_stretch*srcSampleRate))-startsamp)*transport_trig_rate.reciprocal).ceil;
              transportDuration=(transportNumFrames/srcSampleRate);
              
              ["transportDuration: ", transportDuration].postln;
              players.at(\currentPlayer).set(\transport_rate,transport_rate);
              transportDuration.wait;
              playing_slice=false;
              sc_sender.sendMsg("/sc_fcm2dcorpus/record_transportbuf")
            }).play;
          });
        });
      }, "/sc_fcm2dcorpus/transport_slices");
    );

    osc_funcs.put("transport_gate",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var gate=msg[1];
        ["transport_gate",gate].postln;
        players.at(\currentPlayer).set(\gate,gate);
      }, "/sc_fcm2dcorpus/transport_gate");
    );
    
    osc_funcs.put("set_transport_volume",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var vol=msg[1];
        transport_volume=vol;
        players.at(\currentPlayer).set(\tamp,vol);
      }, "/sc_fcm2dcorpus/set_transport_volume");
    );


    osc_funcs.put("transport_x_y",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var x=msg[1];
        var y=msg[2];
        players.at(\currentPlayer).set(\xloc,x);
        players.at(\currentPlayer).set(\yloc,y);
        sc_sender.sendMsg("/sc_fcm2dcorpus/record_transportbuf")
      }, "/sc_fcm2dcorpus/transport_x_y");
    );

    osc_funcs.put("set_transport_rate",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var rate=msg[1];
        transport_rate=rate;
        players.at(\currentPlayer).set(\transport_rate,rate);
      }, "/sc_fcm2dcorpus/set_transport_rate");
    );

    osc_funcs.put("set_transport_trig_rate",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var rate=msg[1];
        transport_trig_rate=rate;
        players.at(\currentPlayer).set(\transport_trig_rate,rate);
      }, "/sc_fcm2dcorpus/set_transport_trig_rate");
    );

    osc_funcs.put("set_transport_reset_pos",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var pos=msg[1];
        transport_reset_pos=pos;
        players.at(\currentPlayer).set(\reset_pos,pos);
      }, "/sc_fcm2dcorpus/set_transport_reset_pos");
    );

    osc_funcs.put("set_transport_stretch",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var stretch=msg[1];
        transport_stretch=stretch;
        players.at(\currentPlayer).set(\stretch,stretch);
        sc_sender.sendMsg("/sc_fcm2dcorpus/record_transportbuf")
      }, "/sc_fcm2dcorpus/set_transport_stretch");
    );

    osc_funcs.put("record_transportbuf",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var path=audio_path ++ "temp/transport.wav";
        var header_format="WAV";
        var sample_format="int24";

        var startsamp=findices[current_slice.asInteger];
        var stopsamp=findices[current_slice.asInteger+1];
        var startsamp_prev=findices[previous_slice.asInteger];
        var stopsamp_prev=findices[previous_slice.asInteger+1];
        var transportNumFrames,transportNumFrames_prev,transportDuration;
        transportNumFrames=(((stopsamp+(transport_stretch*ssample_rate))-startsamp)).ceil;
        transportNumFrames_prev=(((stopsamp_prev+(transport_stretch*ssample_rate))-startsamp_prev)).ceil;
        // var transportNumFrames=(((stopsamp+(transport_stretch*ssample_rate))-startsamp)*transport_trig_rate.reciprocal).ceil;
        
        transportNumFrames = LinLin.kr(x_loc,0,1,transportNumFrames,transportNumFrames_prev);
        transportDuration=(transportNumFrames/ssample_rate);
        [buf_writing,buf_recording].postln;
        // if((buf_recording==0).and(buf_writing==0),{
        if(buf_writing==0,{
          buf_recording=1;
          players.at(\currentPlayer).set(\buf_recording,1);

          ["start record transport buffer ",
            transportBuffer,
            transportDuration,
            transportNumFrames
          ].postln;
          ["buf_recording",buf_recording].postln;
          // Tdef(\record_transportbuf_timer).clear;
          
          Tdef(\record_transportbuf_timer, { 
            loop { 
              transportDuration.wait;
              sc_sender.sendMsg("/sc_fcm2dcorpus/on_transport_buffer_recorded");
              Tdef(\record_transportbuf_timer).stop;
              buf_recording=0;
              players.at(\currentPlayer).set(\buf_recording,0);
              // Tdef(\record_transportbuf_timer).clear;
            }.play; 
          }).play;
        });
      }, "/sc_fcm2dcorpus/record_transportbuf");
    );

    osc_funcs.put("on_transport_buffer_recorded",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var path=audio_path ++ "temp/transport.wav";
        // var num_frames=msg[1];
        var header_format="WAV";
        var sample_format="int24";
        ["transport buffer recorded"].postln;
        
        writebuf.(transportBuffer,path,header_format,sample_format,-1,"transport");
        
        // pos.postln;
        // lua_sender.sendMsg("/lua_fcm2dcorpus/transport_sig_pos",pos);
      }, "/sc_fcm2dcorpus/on_transport_buffer_recorded");
    );

    osc_funcs.put("transport_sig_pos",
      OSCFunc.new({ |msg, time, addr, recvPort|
        var pos=msg[3];
        // pos.postln;
        lua_sender.sendMsg("/lua_fcm2dcorpus/transport_sig_pos",pos);
      }, "/sc_fcm2dcorpus/transport_sig_pos");
    );

  }

  free {
    "free FCM2dCorpus".postln;  
    eglut.free();
    osc_funcs.keysValuesDo({ arg k, val;
      val.free;
    });
    recorders.keysValuesDo({ arg k, val;
      val.free;
    });
    players.keysValuesDo({ arg k, val;
      val.free;
    });
  }
}
