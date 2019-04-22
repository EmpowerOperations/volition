using EmpowerOps.Volition.DTO;
using Google.Protobuf.Collections;
using Grpc.Core;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Forms;
using static EmpowerOps.Volition.DTO.NodeStatusCommandOrResponseDTO.Types;
using MessageBox = System.Windows.MessageBox;

namespace EmpowerOps.Volition.RefClient
{

    /// <summary>
    /// Interaction logic for MainWindow.xaml
    /// </summary>
    public partial class MainWindow : Window
    {
        private readonly Optimizer.OptimizerClient _client;
        private readonly Channel _channel;
        private AsyncServerStreamingCall<RequestQueryDTO> _requests;
        private ChannelState channelState;
        private string _name = "";
        private bool _isRegistered = false;
        private readonly BindingSource _inputSource = new BindingSource();
        private readonly BindingSource _outputSource = new BindingSource();
        private Guid _latestRunID = Guid.Empty;
        private Guid _activeRunID = Guid.Empty;
        private List<Guid> _runIDs = new List<Guid>();
        private string _serverPrefix = "Server:";
        private string _commandPrefix = ">";
        private IEvaluator _randomNumberEvaluator = new RandomNumberEvaluator();
        public MainWindow()
        {
            //https://grpc.io/docs/quickstart/csharp.html#update-the-client
            _channel = new Channel("localhost:5550", ChannelCredentials.Insecure);
            _client = new Optimizer.OptimizerClient(_channel);
            InitializeComponent();
            UpdateConnectionStatus();
            UpdateButton();
            UpdateButton();
            ConfigGrid();
        }

        private void ConfigGrid()
        {
            InputGrid.ItemsSource = _inputSource;
            OutputGrid.ItemsSource = _outputSource;
        }


        private async void StartOptimization_Click(object sender, RoutedEventArgs e)
        {
            if (!_isRegistered)
            {
                ShowNotRegisterMessage();
                return;
            }
            Log($"{_commandPrefix} Start Requested");
            try
            {
                var startResponse = await _client.startOptimizationAsync(new StartOptimizationCommandDTO
                {
                    Name = _name
                });
                if (startResponse.Acknowledged)
                {
                    Log($"{_serverPrefix} Start Reqeust received - awating run ID");
                }
                else
                {
                    MessageBox.Show($"Optimizer can not start the run. {Environment.NewLine}Issues: {startResponse.Message}");
                    Log($"{_serverPrefix} {startResponse.Message}");
                }
            }
            catch (RpcException exception)
            {
                Log($"{_serverPrefix} Error invoke \"startOptimizationAsync()\" Exception: {exception.Status}");
            }

        }

        private void ApplyTimeout_Click(object sender, RoutedEventArgs e)
        {
            if (!_isRegistered)
            {
                ShowNotRegisterMessage();
                return;
            }
            if (int.TryParse(TimeoutTextBox.Text, out int timeout) && timeout > 0)
            {
                ApplyTimeout(timeout);
            }
            else
            {
                TimeoutTextBox.Text = "0";
                ApplyTimeout(0);
                MessageBox.Show("Invalid time out, please input a postive integer value");
            }

        }

        private async void ApplyTimeout(int timeout)
        {
            Log($"{_commandPrefix} Try to apply timeout {timeout}");
            var configurationResponseDto = await _client.updateConfigurationAsync(new ConfigurationCommandDTO
            {
                Name = _name,
                Config = new ConfigurationCommandDTO.Types.Config
                {
                    Timeout = timeout
                }
            });
            Log($"{_serverPrefix} {configurationResponseDto.Message}");

        }

        private void ClearTimeout_Click(object sender, RoutedEventArgs e)
        {
            TimeoutTextBox.Text = "0";
            _client.updateConfiguration(new ConfigurationCommandDTO
            {
                Name = _name,
                Config = new ConfigurationCommandDTO.Types.Config
                {
                    Timeout = 0
                }
            });
            MessageBox.Show("Timeout cleared");
        }

        private void StopOptimization_Click(object sender, RoutedEventArgs e)
        {
            if (!_isRegistered)
            {
                ShowNotRegisterMessage();
                return;
            }
            Log($"{_commandPrefix} Request Stop - ID:{_activeRunID}");
            var stopOptimizationResponseDto = _client.stopOptimization(new StopOptimizationCommandDTO()
            {
                Name = _name,
                Id = _activeRunID.ToString()
            });
            switch (stopOptimizationResponseDto.ResponseCase)
            {
                case StopOptimizationResponseDTO.ResponseOneofCase.Message:
                    Log($"{_serverPrefix} {stopOptimizationResponseDto.Message}");
                    break;
                case StopOptimizationResponseDTO.ResponseOneofCase.RunID:
                    Log($"{_serverPrefix} Stop Reqeust received - Stopping RunID:{stopOptimizationResponseDto.RunID}");
                    break;
            }
            _activeRunID = Guid.Empty;
        }

        private async void RequestResult_Click(object sender, RoutedEventArgs e)
        {
            var resultResponseDto = await requestRunResult(_latestRunID);
            switch (resultResponseDto.ResponseCase)
            {
                case ResultResponseDTO.ResponseOneofCase.Message:
                    Log($"{_serverPrefix} {resultResponseDto.Message}");
                    break;
                case ResultResponseDTO.ResponseOneofCase.RunResult:
                    Log($"{_serverPrefix} Result:{Environment.NewLine}{String.Join(Environment.NewLine, resultResponseDto.RunResult.Point)}");
                    break;
            }


        }

        private async Task<ResultResponseDTO> requestRunResult(Guid runId)
        {
            Log($"{_commandPrefix} Request run result - ID:{runId}");
            return await _client.requestRunResultAsync(new ResultRequestDTO()
            {
                Name = _name,
                RunID = runId.ToString()
            });
        }

        private async void UpdateConnectionStatus()
        {
            channelState = _channel.State;
            ConnectionStatus.Text = $"Connection: {channelState.ToString()}";
            while (true)
            {
                await _channel.WaitForStateChangedAsync(channelState);
                channelState = _channel.State;
                ConnectionStatus.Text = $"Connection: {channelState.ToString()}";
            }

        }

        private void UpdateButton()
        {
            RegistrationStatus.Text = _isRegistered ? $"Registered as {_name}" : $"Not registered";
        }

        private async Task HandlingRequestsAsync(AsyncServerStreamingCall<RequestQueryDTO> requests)
        {
            //request for inputs, do the simulation, return result, repeat
            var requestsResponseStream = requests.ResponseStream;
            try
            {
                while (await requestsResponseStream.MoveNext(new CancellationToken()))
                {
                    HandleRequestAsync(requestsResponseStream.Current);
                }

                Log($"{_commandPrefix} Query Closed, plugin has been unregisred by server");
            }
            catch (Exception e)
            {
                Log($"{_commandPrefix} Error happened when reading from request stream, unregistered\n{e}");
            }
            finally
            {
                _name = "";
                _requests = null;
                _isRegistered = false;
                UpdateButton();
            }

        }

        private async void HandleRequestAsync(RequestQueryDTO request)
        {
            try
            {
                switch (request.RequestCase)
                {
                    case RequestQueryDTO.RequestOneofCase.EvaluationRequest:
                        {
                            Log($"{_serverPrefix} Request Evaluation");
                            EvaluateAsync(request);
                            break;
                        }
                    case RequestQueryDTO.RequestOneofCase.NodeStatusRequest:
                        Log($"{_serverPrefix} Request Node Status");
                        await _client.offerSimulationConfigAsync(BuildNodeUpdateResponse());
                        break;
                    case RequestQueryDTO.RequestOneofCase.CancelRequest:
                        Log($"{_serverPrefix} Request Cancel");
                        _randomNumberEvaluator.Cancel();
                        break;
                    case RequestQueryDTO.RequestOneofCase.StartRequest:
                        Log($"{_serverPrefix} Run Start - ID:{request.StartRequest.RunID}");
                        _activeRunID = Guid.Parse(request.StartRequest.RunID);
                        _latestRunID = _activeRunID;
                        _runIDs.Add(_activeRunID);
                        break;
                    case RequestQueryDTO.RequestOneofCase.StopRequest:
                        Log($"{_serverPrefix} Run Stop - ID:{request.StopRequest.RunID}");
                        _activeRunID = Guid.Empty;
                        break;
                    case RequestQueryDTO.RequestOneofCase.None:
                        break;
                    default:
                        break;
                }
            }
            catch (Exception e)
            {
                await _client.offerErrorResultAsync(new ErrorResponseDTO
                {
                    Name = _name,
                    Message = $"Error handling request [{request.RequestCase}]",
                    Exception = e.ToString()
                });
            }

        }

        private async void EvaluateAsync(RequestQueryDTO request)
        {
            MapField<string, double> inputs = request.EvaluationRequest.InputVector;
            Log($"{_commandPrefix} Requested Input: [{inputs}]");

            foreach (Input input in _inputSource)
            {
                input.EvaluatingValue = inputs[input.Name];
            }
            foreach (Output output in _outputSource)
            {
                output.EvaluatingValue = "Evaluating...";
            }
            UpdateBindingSourceOnUI(_inputSource);
            UpdateBindingSourceOnUI(_outputSource);

            Log($"{_commandPrefix} Evaluating...");

            var result = await _randomNumberEvaluator.EvaluateAsync(inputs, _outputSource.List);

            foreach (Input input in _inputSource)
            {
                input.CurrentValue = inputs[input.Name];
            }

            foreach (Output output in _outputSource)
            {
                if (result.Output.ContainsKey(output.Name))
                {
                    output.CurrentValue = result.Output[output.Name].ToString();
                    output.EvaluatingValue = result.Output[output.Name].ToString();
                }
                else
                {
                    output.CurrentValue = result.Status.ToString();
                    output.EvaluatingValue = result.Status.ToString();
                }
            }

            UpdateBindingSourceOnUI(_inputSource);
            UpdateBindingSourceOnUI(_outputSource);
            switch (result.Status)
            {
                case EvaluationResult.ResultStatus.Succeed:
                    Log($"{_commandPrefix} Evaluation Succeed [{ToDebugString(result.Output)}]");
                    SimulationResponseDTO request1 = new SimulationResponseDTO
                    {
                        Name = _name,
                        OutputVector = { result.Output }
                    };
                    await _client.offerSimulationResultAsync(request1);
                    break;
                case EvaluationResult.ResultStatus.Failed:
                    Log($"{_commandPrefix} Evaluation Failed [{ToDebugString(result.Output)}]\nException: {result.Exception}");
                    await _client.offerErrorResultAsync(new ErrorResponseDTO
                    {
                        Name = _name,
                        Message = $"{_commandPrefix} Evaluation Failed when evaluating [{inputs}]",
                        Exception = result.Exception.ToString()
                    });
                    break;
                case EvaluationResult.ResultStatus.Canceled:
                    Log($"{_commandPrefix} Evaluation Canceled");
                    await _client.offerSimulationResultAsync(new SimulationResponseDTO
                    {
                        Name = _name,
                        OutputVector = { result.Output }
                    });
                    break;
                default:
                    throw new ArgumentOutOfRangeException();
            }
        }

        public static String ToDebugString(IDictionary<string, double> dictionary)
        {
            return $"{{{ string.Join(",", dictionary.Select(it => $"\"{it.Key}\": {it.Value}").ToArray())}}}";
        }

        private async void UpdateNode()
        {
            await _client.updateNodeAsync(BuildNodeUpdateResponse());
        }

        private NodeStatusCommandOrResponseDTO BuildNodeUpdateResponse()
        {
            var nodeStatusCommandOrResponseDto = new NodeStatusCommandOrResponseDTO
            {
                Name = _name,
                Description = "Volition Reference Client"
            };
            //gather inputs/ outputs NodeStatusCommandOrResponseDTO
            foreach (Input input in _inputSource)
            {
                nodeStatusCommandOrResponseDto.Inputs.Add(new PrototypeInputParameter
                {
                    Name = input.Name,
                    LowerBound = input.LowerBound,
                    UpperBound = input.UpperBound,
                });
            }

            foreach (Output output in _outputSource)
            {
                nodeStatusCommandOrResponseDto.Outputs.Add(new PrototypeOutputParameter
                {
                    Name = output.Name
                });
            }

            return nodeStatusCommandOrResponseDto;
        }

        private async void Register_Click(object sender, RoutedEventArgs e)
        {
            //TODO better error state handing, when register failed due to no connection, it is not well though right now
            //TODO also when error flow when register e.g. same name
            var registrationCommandDto = new RequestRegistrationCommandDTO { Name = RegName.Text };
            if (_requests != null)
            {
                Log($"{_commandPrefix} Node already registered");
                return;
            }

            Log($"{_commandPrefix} Try Register as {RegName.Text}");
            _requests = _client.registerRequest(registrationCommandDto);
            if (_channel.State != ChannelState.Ready)
            {
                await _channel.WaitForStateChangedAsync(_channel.State);
            }

            if (_channel.State == ChannelState.Ready)
            {
                Log($"{_commandPrefix} Registered");
                _isRegistered = true;
                _name = registrationCommandDto.Name;
                UpdateButton();
                await HandlingRequestsAsync(_requests);
            }
            else
            {
                Log($"{_commandPrefix} Connection Failed, Not Registered");
                _requests = null;
                _isRegistered = false;
                _name = null;
                UpdateButton();
            }
        }

        private async void Rename_Button_Click(object sender, RoutedEventArgs e)
        {
            if (!_isRegistered)
            {
                ShowNotRegisterMessage();
                return;
            }

            var nodeNameChangeCommandDto = new NodeNameChangeCommandDTO { OldName = _name, NewName = RegName.Text };

            NodeNameChangeResponseDTO nodeNameChangeResponseDto = await _client.changeNodeNameAsync(nodeNameChangeCommandDto);
            if (nodeNameChangeResponseDto.Changed)
            {
                MessageBox.Show($"Change name {nodeNameChangeCommandDto.OldName} to {nodeNameChangeCommandDto.NewName} succeed.");
                _name = nodeNameChangeCommandDto.NewName;
                RegisterLabel.Content = $"Registered as {_name}";
            }
            else
            {
                MessageBox.Show($"Change name {nodeNameChangeCommandDto.OldName} to {nodeNameChangeCommandDto.NewName} failed. ${nodeNameChangeResponseDto.Message}");
            }
        }

        private async void Log(string message)
        {

            LogInfoTextBox.Text = LogInfoTextBox.Text += message + "\n";
            LogInfoTextBox.ScrollToEnd();
            if (_channel.State == ChannelState.Ready && ForwardMessageCheckBox.IsChecked.GetValueOrDefault(false))
            {
                await _client.sendMessageAsync(new MessageCommandDTO() { Name = _name ?? "no_name", Message = message });
            }

        }

        private void UpdateBindingSourceOnUI(BindingSource bindingSource)
        {
            bindingSource.ResetBindings(false);
        }

        private void AddInput_Button_Click(object sender, RoutedEventArgs e)
        {
            _inputSource.Add(new Input
            {
                Name = "x1",
                LowerBound = 0.0,
                UpperBound = 10.0
            });

        }

        private void AddOutput_Button_Click(object sender, RoutedEventArgs e)
        {
            _outputSource.Add(new Output
            {
                Name = "f1"
            });
        }

        private void RemoveInput_Button_Click(object sender, RoutedEventArgs e)
        {
            _inputSource.Remove(InputGrid.SelectedItem);
        }

        private void RemoveOutput_Button_Click(object sender, RoutedEventArgs e)
        {
            _outputSource.Remove(OutputGrid.SelectedItem);
        }

        private void UpdateButton_Click(object sender, RoutedEventArgs e)
        {
            if (!_isRegistered)
            {
                ShowNotRegisterMessage();
                return;
            }
            UpdateNode();
        }

        private void UnRegister_Click(object sender, RoutedEventArgs e)
        {
            if (!_isRegistered)
            {
                ShowNotRegisterMessage();
                return;
            }

            Unregister();
        }

        private async void Unregister()
        {
            Log($"{_commandPrefix} Try Unregister {_name}");
            var responseDto = await _client.unregisterRequestAsync(new RequestUnRegistrationRequestDTO() { Name = _name });
            Log($"{_serverPrefix} {responseDto.Message}");  //We dont care the return result and consider ourself as unregsisted
            _name = "";
            _requests = null;
            _isRegistered = false;
            UpdateButton();
        }


        private void FailNextRun_Click(object sender, RoutedEventArgs e)
        {
            _randomNumberEvaluator.SetFailNext();
        }

        private void ShowNotRegisterMessage()
        {
            MessageBox.Show("Simulation is not registerd.");
        }

        /**
         * Auto setup will override existing setup and update the optizmer to a
         * single plugin 
         */
        private async void AutoSetupButton_Click(object sender, RoutedEventArgs e)
        {
            if (!_isRegistered)
            {
                ShowNotRegisterMessage();
                return;
            }
            Log($"{_commandPrefix} Auto setup request");
            var nodeChangeConfirmDto = await _client.autoConfigureAsync(BuildNodeUpdateResponse());
            Log($"{_serverPrefix} {nodeChangeConfirmDto.Message}");
        }
    }

}
